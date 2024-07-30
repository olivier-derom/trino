/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.deltalake.delete;

import com.google.common.base.CharMatcher;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoInputFile;
import io.trino.plugin.deltalake.transactionlog.DeletionVectorEntry;
import io.trino.spi.TrinoException;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.longlong.LongBitmapDataProvider;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_INVALID_SCHEMA;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.lang.Math.toIntExact;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.UUID.randomUUID;

// https://github.com/delta-io/delta/blob/master/PROTOCOL.md#deletion-vector-format
public final class DeletionVectors
{
    private static final int PORTABLE_ROARING_BITMAP_MAGIC_NUMBER = 1681511377;
    private static final int MAGIC_NUMBER_BYTE_SIZE = 4;
    private static final int BIT_MAP_COUNT_BYTE_SIZE = 8;
    private static final int BIT_MAP_KEY_BYTE_SIZE = 4;
    private static final int FORMAT_VERSION_V1 = 1;

    private static final String UUID_MARKER = "u"; // relative path with random prefix on disk
    private static final String PATH_MARKER = "p"; // absolute path on disk
    private static final String INLINE_MARKER = "i"; // inline

    private static final CharMatcher ALPHANUMERIC = CharMatcher.inRange('A', 'Z').or(CharMatcher.inRange('a', 'z')).or(CharMatcher.inRange('0', '9')).precomputed();

    private DeletionVectors() {}

    public static Roaring64Bitmap readDeletionVectors(TrinoFileSystem fileSystem, Location location, DeletionVectorEntry deletionVector)
            throws IOException
    {
        if (deletionVector.storageType().equals(UUID_MARKER)) {
            TrinoInputFile inputFile = fileSystem.newInputFile(location.appendPath(toFileName(deletionVector.pathOrInlineDv())));
            byte[] buffer = readDeletionVector(inputFile, deletionVector.offset().orElseThrow(), deletionVector.sizeInBytes());
            Roaring64Bitmap bitmaps = deserializeDeletionVectors(buffer);
            if (bitmaps.getLongCardinality() != deletionVector.cardinality()) {
                throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "The number of deleted rows expects %s but got %s".formatted(deletionVector.cardinality(), bitmaps.getLongCardinality()));
            }
            return bitmaps;
        }
        if (deletionVector.storageType().equals(INLINE_MARKER) || deletionVector.storageType().equals(PATH_MARKER)) {
            throw new TrinoException(NOT_SUPPORTED, "Unsupported storage type for deletion vector: " + deletionVector.storageType());
        }
        throw new IllegalArgumentException("Unexpected storage type: " + deletionVector.storageType());
    }

    public static DeletionVectorEntry writeDeletionVectors(
            TrinoFileSystem fileSystem,
            Location location,
            Roaring64Bitmap pastDeletionVectors,
            LongBitmapDataProvider rowsDeletedByDelete,
            LongBitmapDataProvider rowsDeletedByUpdate)
            throws IOException
    {
        UUID uuid = randomUUID();
        String deletionVectorFilename = "deletion_vector_" + uuid + ".bin";
        String pathOrInlineDv = encodeUuid(uuid);
        RoaringBitmap bitmap = toRoaringBitmap(pastDeletionVectors, rowsDeletedByDelete, rowsDeletedByUpdate);
        int sizeInBytes = MAGIC_NUMBER_BYTE_SIZE + BIT_MAP_COUNT_BYTE_SIZE + BIT_MAP_KEY_BYTE_SIZE + bitmap.serializedSizeInBytes();
        long cardinality = pastDeletionVectors.getIntCardinality() + rowsDeletedByDelete.getLongCardinality() + rowsDeletedByUpdate.getLongCardinality();

        checkArgument(sizeInBytes > 0, "sizeInBytes must be positive: %s", sizeInBytes);
        checkArgument(cardinality > 0, "cardinality must be positive: %s", cardinality);

        OptionalInt offset;
        byte[] data = serializeAsByteArray(bitmap, sizeInBytes);
        try (DataOutputStream output = new DataOutputStream(fileSystem.newOutputFile(location.appendPath(deletionVectorFilename)).create())) {
            output.writeByte(FORMAT_VERSION_V1);
            offset = OptionalInt.of(output.size());
            output.writeInt(sizeInBytes);
            output.write(data);
            output.writeInt(calculateChecksum(data));
        }

        return new DeletionVectorEntry(UUID_MARKER, pathOrInlineDv, offset, sizeInBytes, cardinality);
    }

    private static byte[] serializeAsByteArray(RoaringBitmap bitmap, int sizeInBytes)
    {
        ByteBuffer buffer = ByteBuffer.allocate(sizeInBytes).order(LITTLE_ENDIAN);
        buffer.putInt(PORTABLE_ROARING_BITMAP_MAGIC_NUMBER);
        buffer.putLong(1); // Always write single RoaringBitmap
        buffer.putInt(0); // Bitmap index
        bitmap.serialize(buffer);
        return buffer.array();
    }

    private static RoaringBitmap toRoaringBitmap(Roaring64Bitmap pastDeletionVectors, LongBitmapDataProvider rowsDeletedByDelete, LongBitmapDataProvider rowsDeletedByUpdate)
    {
        RoaringBitmap roaringBitmap = new RoaringBitmap();
        pastDeletionVectors.forEach(position -> roaringBitmap.add(toIntExact(position)));
        rowsDeletedByDelete.stream().forEach(position -> roaringBitmap.add(toIntExact(position)));
        rowsDeletedByUpdate.stream().forEach(position -> roaringBitmap.add(toIntExact(position)));
        return roaringBitmap;
    }

    public static String toFileName(String pathOrInlineDv)
    {
        int randomPrefixLength = pathOrInlineDv.length() - Base85Codec.ENCODED_UUID_LENGTH;
        String randomPrefix = pathOrInlineDv.substring(0, randomPrefixLength);
        checkArgument(ALPHANUMERIC.matchesAllOf(randomPrefix), "Random prefix must be alphanumeric: %s", randomPrefix);
        String prefix = randomPrefix.isEmpty() ? "" : randomPrefix + "/";
        String encodedUuid = pathOrInlineDv.substring(randomPrefixLength);
        UUID uuid = decodeUuid(encodedUuid);
        return "%sdeletion_vector_%s.bin".formatted(prefix, uuid);
    }

    public static byte[] readDeletionVector(TrinoInputFile inputFile, int offset, int expectedSize)
            throws IOException
    {
        byte[] bytes = new byte[expectedSize];
        try (DataInputStream inputStream = new DataInputStream(inputFile.newStream())) {
            checkState(inputStream.skip(offset) == offset);
            int actualSize = inputStream.readInt();
            if (actualSize != expectedSize) {
                throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "The size of deletion vector %s expects %s but got %s".formatted(inputFile.location(), expectedSize, actualSize));
            }
            inputStream.readFully(bytes);
            int checksum = inputStream.readInt();
            if (calculateChecksum(bytes) != checksum) {
                throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Checksum mismatch for deletion vector: " + inputFile.location());
            }
        }
        return bytes;
    }

    private static int calculateChecksum(byte[] data)
    {
        // Delta Lake allows integer overflow intentionally because it's fine from checksum perspective
        // https://github.com/delta-io/delta/blob/039a29abb4abc72ac5912651679233dc983398d6/spark/src/main/scala/org/apache/spark/sql/delta/storage/dv/DeletionVectorStore.scala#L115
        Checksum crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    private static Roaring64Bitmap deserializeDeletionVectors(byte[] bytes)
            throws IOException
    {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
        checkArgument(buffer.order() == LITTLE_ENDIAN, "Byte order must be little endian: %s", buffer.order());
        int magicNumber = buffer.getInt();
        if (magicNumber == PORTABLE_ROARING_BITMAP_MAGIC_NUMBER) {
            int size = toIntExact(buffer.getLong());
            Roaring64Bitmap bitmaps = new Roaring64Bitmap();
            for (int i = 0; i < size; i++) {
                int key = buffer.getInt();
                checkArgument(key >= 0, "key must not be negative: %s", key);

                RoaringBitmap bitmap = new RoaringBitmap();
                bitmap.deserialize(buffer);
                bitmap.stream().forEach(bitmaps::add);

                // there seems to be no better way to ask how many bytes bitmap.deserialize has read
                int consumedBytes = bitmap.serializedSizeInBytes();
                buffer.position(buffer.position() + consumedBytes);
            }
            return bitmaps;
        }
        throw new IllegalArgumentException("Unsupported magic number: " + magicNumber);
    }

    public static String encodeUuid(UUID uuid)
    {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        buffer.rewind();
        return Base85Codec.encode(buffer);
    }

    public static UUID decodeUuid(String encoded)
    {
        byte[] bytes = Base85Codec.decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        checkArgument(buffer.remaining() == 16);
        long highBits = buffer.getLong();
        long lowBits = buffer.getLong();
        return new UUID(highBits, lowBits);
    }
}
