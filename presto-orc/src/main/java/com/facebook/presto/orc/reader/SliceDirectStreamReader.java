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
package com.facebook.presto.orc.reader;

import com.facebook.presto.orc.OrcCorruptionException;
import com.facebook.presto.orc.QualifyingSet;
import com.facebook.presto.orc.StreamDescriptor;
import com.facebook.presto.orc.metadata.ColumnEncoding;
import com.facebook.presto.orc.stream.BooleanInputStream;
import com.facebook.presto.orc.stream.ByteArrayInputStream;
import com.facebook.presto.orc.stream.InputStreamSource;
import com.facebook.presto.orc.stream.InputStreamSources;
import com.facebook.presto.orc.stream.LongInputStream;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.VariableWidthBlock;
import com.facebook.presto.spi.type.Type;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.facebook.presto.orc.metadata.Stream.StreamKind.DATA;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.LENGTH;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.PRESENT;
import static com.facebook.presto.orc.reader.SliceStreamReader.computeTruncatedLength;
import static com.facebook.presto.orc.reader.SliceStreamReader.getMaxCodePointCount;
import static com.facebook.presto.orc.stream.MissingInputStreamSource.missingStreamSource;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spi.type.Chars.isCharType;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.airlift.slice.Slices.EMPTY_SLICE;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class SliceDirectStreamReader
        extends ColumnReader
        implements StreamReader
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(SliceDirectStreamReader.class).instanceSize();
    private static final int ONE_GIGABYTE = toIntExact(new DataSize(1, GIGABYTE).toBytes());

    private final StreamDescriptor streamDescriptor;

    private int readOffset;
    private int nextBatchSize;

    private InputStreamSource<BooleanInputStream> presentStreamSource = missingStreamSource(BooleanInputStream.class);
    @Nullable
    private BooleanInputStream presentStream;

    private InputStreamSource<LongInputStream> lengthStreamSource = missingStreamSource(LongInputStream.class);
    @Nullable
    private LongInputStream lengthStream;

    private InputStreamSource<ByteArrayInputStream> dataByteSource = missingStreamSource(ByteArrayInputStream.class);
    @Nullable
    private ByteArrayInputStream dataStream;

    private boolean rowGroupOpen;

    // Number of values in bytes, resultOffsets, valueIsNull before scan().
    int numValues;
    // Number of result rows in scan() so far.
    private int numResults;
    // Content bytes to be returned in Block.
    private byte[] bytes;
    // Start offsets for use in returned Block.
    private int[] resultOffsets;
    // Null flags for use in result Block.
    boolean[] valueIsNull;
    // Temp space for extracting values to filter when a value straddles buffers.
    private byte[] tempBytes;
    // Lengths for the rows of the input QualifyingSet.
    private int[] lengths;
    //Present flag for each row in input QualifyingSet.
    boolean[] present;
    // position of first element of lengths from the start of RowGroup.
    int posInRowGroup;
    // positions of non-null values from start of row group. Set only if presentStream != null.
    int[] nonNullRows;
    // Result arrays from outputQualifyingSet.
    int[] outputRows;
    int[] resultInputNumbers;

    public SliceDirectStreamReader(StreamDescriptor streamDescriptor)
    {
        this.streamDescriptor = requireNonNull(streamDescriptor, "stream is null");
    }

    @Override
    public void prepareNextRead(int batchSize)
    {
        readOffset += nextBatchSize;
        nextBatchSize = batchSize;
    }

    @Override
    public Block readBlock(Type type)
            throws IOException
    {
        if (!rowGroupOpen) {
            openRowGroup();
        }

        if (readOffset > 0) {
            if (presentStream != null) {
                // skip ahead the present bit reader, but count the set bits
                // and use this as the skip size for the length reader
                readOffset = presentStream.countBitsSet(readOffset);
            }
            if (readOffset > 0) {
                if (lengthStream == null) {
                    throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but length stream is not present");
                }
                long dataSkipSize = lengthStream.sum(readOffset);
                if (dataSkipSize > 0) {
                    if (dataStream == null) {
                        throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but data stream is not present");
                    }
                    dataStream.skip(dataSkipSize);
                }
            }
        }

        // create new isNullVector and offsetVector for VariableWidthBlock
        boolean[] isNullVector = null;

        // We will use the offsetVector as the buffer to read the length values from lengthStream,
        // and the length values will be converted in-place to an offset vector.
        int[] offsetVector = new int[nextBatchSize + 1];

        if (presentStream == null) {
            if (lengthStream == null) {
                throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but length stream is not present");
            }
            lengthStream.nextIntVector(nextBatchSize, offsetVector, 0);
        }
        else {
            isNullVector = new boolean[nextBatchSize];
            int nullValues = presentStream.getUnsetBits(nextBatchSize, isNullVector);
            if (nullValues != nextBatchSize) {
                if (lengthStream == null) {
                    throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but length stream is not present");
                }

                if (nullValues == 0) {
                    isNullVector = null;
                    lengthStream.nextIntVector(nextBatchSize, offsetVector, 0);
                }
                else {
                    lengthStream.nextIntVector(nextBatchSize, offsetVector, 0, isNullVector);
                }
            }
        }

        // Calculate the total length for all entries. Note that the values in the offsetVector are still length values now.
        long totalLength = 0;
        for (int i = 0; i < nextBatchSize; i++) {
            if (isNullVector == null || !isNullVector[i]) {
                totalLength += offsetVector[i];
            }
        }

        int currentBatchSize = nextBatchSize;
        readOffset = 0;
        nextBatchSize = 0;
        if (totalLength == 0) {
            return new VariableWidthBlock(currentBatchSize, EMPTY_SLICE, offsetVector, Optional.ofNullable(isNullVector));
        }
        if (totalLength > ONE_GIGABYTE) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR,
                    format("Values in column \"%s\" are too large to process for Presto. %s column values are larger than 1GB [%s]", streamDescriptor.getFieldName(), nextBatchSize, streamDescriptor.getOrcDataSourceId()));
        }
        if (dataStream == null) {
            throw new OrcCorruptionException(streamDescriptor.getOrcDataSourceId(), "Value is not null but data stream is not present");
        }

        // allocate enough space to read
        byte[] data = new byte[toIntExact(totalLength)];
        Slice slice = Slices.wrappedBuffer(data);

        // We do the following operations together in the for loop:
        // * truncate strings
        // * convert original length values in offsetVector into truncated offset values
        int currentLength = offsetVector[0];
        offsetVector[0] = 0;
        int maxCodePointCount = getMaxCodePointCount(type);
        boolean isCharType = isCharType(type);
        for (int i = 1; i <= currentBatchSize; i++) {
            int nextLength = offsetVector[i];
            if (isNullVector != null && isNullVector[i - 1]) {
                checkState(currentLength == 0, "Corruption in slice direct stream: length is non-zero for null entry");
                offsetVector[i] = offsetVector[i - 1];
                currentLength = nextLength;
                continue;
            }
            int offset = offsetVector[i - 1];

            // read data without truncation
            dataStream.next(data, offset, offset + currentLength);

            // adjust offsetVector with truncated length
            int truncatedLength = computeTruncatedLength(slice, offset, currentLength, maxCodePointCount, isCharType);
            verify(truncatedLength >= 0);
            offsetVector[i] = offset + truncatedLength;

            currentLength = nextLength;
        }

        // this can lead to over-retention but unlikely to happen given truncation rarely happens
        return new VariableWidthBlock(currentBatchSize, slice, offsetVector, Optional.ofNullable(isNullVector));
    }

    private void openRowGroup()
            throws IOException
    {
        presentStream = presentStreamSource.openStream();
        lengthStream = lengthStreamSource.openStream();
        dataStream = dataByteSource.openStream();
        posInRowGroup = 0;
        rowGroupOpen = true;
    }

    @Override
    public void startStripe(InputStreamSources dictionaryStreamSources, List<ColumnEncoding> encoding)
    {
        presentStreamSource = missingStreamSource(BooleanInputStream.class);
        lengthStreamSource = missingStreamSource(LongInputStream.class);
        dataByteSource = missingStreamSource(ByteArrayInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        lengthStream = null;
        dataStream = null;

        rowGroupOpen = false;
    }

    @Override
    public void startRowGroup(InputStreamSources dataStreamSources)
    {
        presentStreamSource = dataStreamSources.getInputStreamSource(streamDescriptor, PRESENT, BooleanInputStream.class);
        lengthStreamSource = dataStreamSources.getInputStreamSource(streamDescriptor, LENGTH, LongInputStream.class);
        dataByteSource = dataStreamSources.getInputStreamSource(streamDescriptor, DATA, ByteArrayInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        lengthStream = null;
        dataStream = null;

        rowGroupOpen = false;
    }

    @Override
    public int erase(int begin, int end, int numResultsBeforeRowGroup, int numErasedFromInput)
    {
        if (block != null) {
            block.erase(numResultsBeforeRowGroup + begin, block.getPositionCount());
        }
        if (resultOffsets != null) {
            resultOffsets[0] = 0;
            resultOffsets[1] = 0;
        }
        if (valueIsNull != null) {
            Arrays.fill(valueIsNull, false);
        }
        numValues = 0;
        return 0;
    }

    @Override
    public int scan(int maxBytes)
            throws IOException
    {
        if (resultOffsets == null) {
            resultOffsets = new int[10001];
            bytes = new byte[100000];
            numValues = 0;
            resultOffsets[0] = 0;
            resultOffsets[1] = 0;
            if (filter != null && outputQualifyingSet == null) {
                outputQualifyingSet = new QualifyingSet();
            }
        }
        if (!rowGroupOpen) {
            openRowGroup();
        }
        numResults = 0;
        int numLengths = 0;
        QualifyingSet input = inputQualifyingSet;
        QualifyingSet output = outputQualifyingSet;
        int numInput = input.getPositionCount();
        int end = input.getEnd();
        int rowsInRange = end - posInRowGroup;
        if (presentStream == null) {
            numLengths = end;
        }
        else {
            if (nonNullRows == null || nonNullRows.length < end) {
                nonNullRows = new int[end];
            }
            if (present == null || present.length < end) {
                present = new boolean[end];
            }
            presentStream.getSetBits(rowsInRange, present);
            for (int i = 0; i < end; i++) {
                if (present[i]) {
                    numLengths++;
                    if (filter != null) {
                        nonNullRows[numLengths - 1] = i + posInRowGroup;
                    }
                }
            }
        }
        if (lengths == null || lengths.length < numLengths) {
            lengths = new int[numLengths];
        }
        lengthStream.nextIntVector(numLengths, lengths, 0);
        outputRows = filter != null ? output.getMutablePositions(rowsInRange) : null;
        resultInputNumbers = filter != null ? output.getMutableInputNumbers(rowsInRange) : null;
        int toOffset = 0;
        int[] inputPositions = input.getPositions();
        int lengthIdx = 0;
        int nextActive = inputPositions[0];
        int activeIdx = 0;
        int numActive = input.getPositionCount();
        int toSkip = 0;
        for (int i = 0; i < rowsInRange; i++) {
            if (i + posInRowGroup == nextActive) {
                if (present != null && !present[i]) {
                    if (filter == null || filter.testNull()) {
                        addNullResult(i + posInRowGroup, activeIdx);
                    }
                }
                else {
                    // Non-null row in qualifying set.
                    if (toSkip > 0) {
                        dataStream.skip(toSkip);
                        toSkip = 0;
                    }
                    int length = lengths[lengthIdx];
                    if (filter != null) {
                        byte[] buffer = dataStream.getBuffer(length);
                        int pos = 0;
                        if (buffer == null) {
                            // The value to test is not fully contained in the buffer. Read it to tempBytes.
                            if (tempBytes == null || tempBytes.length < length) {
                                tempBytes = new byte[(int) (length * 1.2)];
                            }
                            buffer = tempBytes;
                            dataStream.next(tempBytes, 0, length);
                            pos = 0;
                        }
                        else {
                            pos = dataStream.getOffsetInBuffer();
                            toSkip += length;
                        }
                        if (filter.testBytes(buffer, pos, length)) {
                            outputRows[numResults] = presentStream != null ? nonNullRows[lengthIdx] : i + posInRowGroup;
                            resultInputNumbers[numResults] = activeIdx;
                            if (outputChannel != -1) {
                                addResultBytes(buffer, pos, length);
                            }
                            numResults++;
                        }
                    }
                    else {
                        // No filter.
                        addResultFromStream(length);
                        numResults++;
                    }
                    lengthIdx++;
                }
                if (++activeIdx == numActive) {
                    for (int i2 = lengthIdx; i2 < numLengths; i2++) {
                        toSkip += lengths[i2];
                    }
                    break;
                }
                nextActive = inputPositions[activeIdx];
                continue;
            }
            else {
                // The row is notg in the input qualifying set. Add length to skip if non-null.
                if (present == null || present[i]) {
                    toSkip += lengths[lengthIdx++];
                }
            }
        }
        if (toSkip > 0) {
            dataStream.skip(toSkip);
        }
        if (output != null) {
            output.setPositionCount(numResults);
        }
        numValues += numResults;
        if (block != null) {
            block.setPositionCount(numValues);
        }
        if (output != null) {
            output.setEnd(end);
        }
        posInRowGroup = end;
        return end;
    }

    void addNullResult(int row, int activeIdx)
    {
        if (outputChannel != -1) {
            if (valueIsNull == null) {
                valueIsNull = new boolean[resultOffsets.length];
            }
            ensureResultRows();
            valueIsNull[numResults + numValues] = true;
            resultOffsets[numValues + numResults + 1] = resultOffsets[numValues + numResults];
        }
        if (filter != null) {
            outputRows[numResults] = row;
            resultInputNumbers[numResults] = activeIdx;
        }
        numResults++;
    }

    void addResultBytes(byte[] buffer, int pos, int length)
    {
        ensureResultBytes(length);
        ensureResultRows();
        int endOffset = resultOffsets[numValues + numResults + 1];
        System.arraycopy(buffer, pos, bytes, endOffset, length);
        resultOffsets[numValues + numResults + 2] = endOffset + length;
    }

    void addResultFromStream(int length)
            throws IOException
    {
        ensureResultBytes(length);
        ensureResultRows();
        int endOffset = resultOffsets[numValues + numResults + 1];
        dataStream.next(bytes, endOffset, length);
        resultOffsets[numValues + numResults + 2] = endOffset + length;
    }

    void ensureResultBytes(int length)
    {
        int offset = resultOffsets[numResults + numValues + 1];
        if (bytes.length < length + offset) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, bytes.length + length));
            block = null;
        }
    }

    void ensureResultRows()
    {
        if (resultOffsets.length <= numValues + numResults + 2) {
            resultOffsets = Arrays.copyOf(resultOffsets, resultOffsets.length * 2);
            if (valueIsNull != null) {
                valueIsNull = Arrays.copyOf(valueIsNull, resultOffsets.length);
            }
            block = null;
        }
    }

    @Override
    public Block getBlock(boolean mayReuse)
    {
        if (block == null) {
            if (bytes == null) {
                // We must be skipping over whole row groups. Make a small bytes to start with.
                bytes = new byte[1024];
            }
            block = new VariableWidthBlock(numValues, Slices.wrappedBuffer(bytes), resultOffsets, valueIsNull == null ? Optional.empty() : Optional.of(valueIsNull));
        }
        Block oldBlock = block;
        if (!mayReuse) {
            resultOffsets = null;
            valueIsNull = null;
            bytes = null;
            block = null;
        }
        return oldBlock;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(streamDescriptor)
                .toString();
    }

    @Override
    public void close()
    {
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE;
    }
}
