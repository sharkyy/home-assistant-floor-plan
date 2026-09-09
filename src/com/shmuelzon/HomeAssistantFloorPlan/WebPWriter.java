package com.shmuelzon.HomeAssistantFloorPlan;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Minimal, dependency free encoder for lossless WebP (VP8L) images.
 *
 * ImageIO ships no WebP writer and the plugin is distributed as a plain jar
 * without native libraries, so the bitstream is produced here. The encoder
 * implements the subtract-green and predictor transforms, LZ77 back references
 * and canonical Huffman coding, which keeps the files in the same size class as
 * PNG while every pixel - including the colour of fully transparent ones - stays
 * bit exact.
 */
public class WebPWriter {
    private static final int MAX_DIMENSION = 1 << 14;
    private static final int NUM_LITERAL_CODES = 256;
    private static final int NUM_LENGTH_CODES = 24;
    private static final int GREEN_CODES = NUM_LITERAL_CODES + NUM_LENGTH_CODES;
    private static final int NUM_DISTANCE_CODES = 40;
    private static final int CODE_LENGTH_CODES = 19;
    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static final int MAX_CODE_LENGTH = 15;
    private static final int MAX_CODE_LENGTH_CODE_LENGTH = 7;
    private static final int PREDICTOR_BITS = 4;
    private static final int NUM_PREDICTORS = 14;
    /* Distances 1..120 address a 2D neighbourhood through a lookup table in the
     * decoder; everything above is a plain pixel distance plus this offset. */
    private static final int PLANE_CODE_OFFSET = 120;
    private static final int MIN_MATCH = 3;
    private static final int MAX_MATCH = 4096;
    private static final int MAX_DISTANCE = (1 << 20) - PLANE_CODE_OFFSET;
    private static final int HASH_BITS = 16;
    private static final int HASH_SIZE = 1 << HASH_BITS;
    private static final int MAX_CHAIN_LENGTH = 32;

    private WebPWriter() {
    }

    public static void write(BufferedImage image, File file) throws IOException {
        byte[] data = encode(image);
        OutputStream stream = new FileOutputStream(file);
        try {
            stream.write(data);
        } finally {
            stream.close();
        }
    }

    public static byte[] encode(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IOException("Unsupported WebP image size: " + width + "x" + height);

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        boolean hasAlpha = false;
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] >>> 24) != 0xff) {
                hasAlpha = true;
                break;
            }
        }

        BitWriter bitWriter = new BitWriter(pixels.length + 1024);
        bitWriter.putBits(0x2f, 8);
        bitWriter.putBits(width - 1, 14);
        bitWriter.putBits(height - 1, 14);
        bitWriter.putBits(hasAlpha ? 1 : 0, 1);
        bitWriter.putBits(0, 3);

        /* Transforms are applied by the decoder in reverse order, so green is
         * subtracted first and the predictor then works on the result. */
        bitWriter.putBits(1, 1);
        bitWriter.putBits(2, 2);
        subtractGreen(pixels);

        bitWriter.putBits(1, 1);
        bitWriter.putBits(0, 2);
        bitWriter.putBits(PREDICTOR_BITS - 2, 3);
        int[] predictorImage = applyPredictorTransform(pixels, width, height);
        int tilesX = subSampleSize(width, PREDICTOR_BITS);
        int tilesY = subSampleSize(height, PREDICTOR_BITS);
        writeImageStream(bitWriter, predictorImage, tilesX, tilesY, false);

        bitWriter.putBits(0, 1);
        writeImageStream(bitWriter, pixels, width, height, true);

        return wrapInRiffContainer(bitWriter.toByteArray());
    }

    private static byte[] wrapInRiffContainer(byte[] vp8l) {
        int padding = vp8l.length & 1;
        byte[] out = new byte[12 + 8 + vp8l.length + padding];
        int offset = 0;
        offset = putFourCc(out, offset, "RIFF");
        offset = putUint32(out, offset, 4 + 8 + vp8l.length + padding);
        offset = putFourCc(out, offset, "WEBP");
        offset = putFourCc(out, offset, "VP8L");
        offset = putUint32(out, offset, vp8l.length);
        System.arraycopy(vp8l, 0, out, offset, vp8l.length);
        return out;
    }

    private static int putFourCc(byte[] out, int offset, String fourCc) {
        for (int i = 0; i < 4; i++)
            out[offset + i] = (byte)fourCc.charAt(i);
        return offset + 4;
    }

    private static int putUint32(byte[] out, int offset, int value) {
        out[offset] = (byte)(value & 0xff);
        out[offset + 1] = (byte)((value >>> 8) & 0xff);
        out[offset + 2] = (byte)((value >>> 16) & 0xff);
        out[offset + 3] = (byte)((value >>> 24) & 0xff);
        return offset + 4;
    }

    private static int subSampleSize(int size, int samplingBits) {
        return (size + (1 << samplingBits) - 1) >> samplingBits;
    }

    private static void subtractGreen(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int green = (argb >> 8) & 0xff;
            int red = (((argb >> 16) & 0xff) - green) & 0xff;
            int blue = ((argb & 0xff) - green) & 0xff;
            pixels[i] = (argb & 0xff00ff00) | (red << 16) | blue;
        }
    }

    /* Replaces every pixel by its residual against the best predictor of its
     * tile and returns the predictor image (mode kept in the green channel). */
    private static int[] applyPredictorTransform(int[] pixels, int width, int height) {
        int tileSize = 1 << PREDICTOR_BITS;
        int tilesX = subSampleSize(width, PREDICTOR_BITS);
        int tilesY = subSampleSize(height, PREDICTOR_BITS);
        int[] modes = new int[tilesX * tilesY];

        for (int tileY = 0; tileY < tilesY; tileY++) {
            for (int tileX = 0; tileX < tilesX; tileX++) {
                int xStart = tileX * tileSize;
                int yStart = tileY * tileSize;
                int xEnd = Math.min(xStart + tileSize, width);
                int yEnd = Math.min(yStart + tileSize, height);
                int bestMode = 0;
                long bestCost = Long.MAX_VALUE;

                for (int mode = 0; mode < NUM_PREDICTORS; mode++) {
                    long cost = 0;
                    for (int y = Math.max(yStart, 1); y < yEnd; y++) {
                        for (int x = Math.max(xStart, 1); x < xEnd; x++) {
                            int index = y * width + x;
                            cost += residualCost(pixels[index], predict(mode, pixels, index, width));
                        }
                    }
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestMode = mode;
                    }
                }
                modes[tileY * tilesX + tileX] = bestMode;
            }
        }

        int[] residuals = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int predicted;
                if (index == 0)
                    predicted = 0xff000000;
                else if (y == 0)
                    predicted = pixels[index - 1];
                else if (x == 0)
                    predicted = pixels[index - width];
                else
                    predicted = predict(modes[(y >> PREDICTOR_BITS) * tilesX + (x >> PREDICTOR_BITS)], pixels, index, width);
                residuals[index] = subtractPixels(pixels[index], predicted);
            }
        }
        System.arraycopy(residuals, 0, pixels, 0, pixels.length);

        int[] predictorImage = new int[modes.length];
        for (int i = 0; i < modes.length; i++)
            predictorImage[i] = 0xff000000 | (modes[i] << 8);
        return predictorImage;
    }

    private static int subtractPixels(int argb, int predicted) {
        return (((argb >>> 24) - (predicted >>> 24)) & 0xff) << 24
            | ((((argb >> 16) & 0xff) - ((predicted >> 16) & 0xff)) & 0xff) << 16
            | ((((argb >> 8) & 0xff) - ((predicted >> 8) & 0xff)) & 0xff) << 8
            | (((argb & 0xff) - (predicted & 0xff)) & 0xff);
    }

    private static long residualCost(int argb, int predicted) {
        return channelCost((argb >>> 24) - (predicted >>> 24))
            + channelCost(((argb >> 16) & 0xff) - ((predicted >> 16) & 0xff))
            + channelCost(((argb >> 8) & 0xff) - ((predicted >> 8) & 0xff))
            + channelCost((argb & 0xff) - (predicted & 0xff));
    }

    private static int channelCost(int difference) {
        int value = difference & 0xff;
        return Math.min(value, 256 - value);
    }

    private static int predict(int mode, int[] pixels, int index, int width) {
        int left = pixels[index - 1];
        int top = pixels[index - width];
        int topLeft = pixels[index - width - 1];
        int topRight = pixels[index - width + 1];

        switch (mode) {
            case 0: return 0xff000000;
            case 1: return left;
            case 2: return top;
            case 3: return topRight;
            case 4: return topLeft;
            case 5: return average2(average2(left, topRight), top);
            case 6: return average2(left, topLeft);
            case 7: return average2(left, top);
            case 8: return average2(topLeft, top);
            case 9: return average2(top, topRight);
            case 10: return average2(average2(left, topLeft), average2(top, topRight));
            case 11: return select(top, left, topLeft);
            case 12: return clampedAddSubtractFull(left, top, topLeft);
            default: return clampedAddSubtractHalf(left, top, topLeft);
        }
    }

    private static int average2(int a, int b) {
        return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b);
    }

    private static int select(int a, int b, int c) {
        int paMinusPb = sub3(a >>> 24, b >>> 24, c >>> 24)
            + sub3((a >> 16) & 0xff, (b >> 16) & 0xff, (c >> 16) & 0xff)
            + sub3((a >> 8) & 0xff, (b >> 8) & 0xff, (c >> 8) & 0xff)
            + sub3(a & 0xff, b & 0xff, c & 0xff);
        return paMinusPb <= 0 ? a : b;
    }

    private static int sub3(int a, int b, int c) {
        return Math.abs(b - c) - Math.abs(a - c);
    }

    private static int clampedAddSubtractFull(int c0, int c1, int c2) {
        int a = clamp255((c0 >>> 24) + (c1 >>> 24) - (c2 >>> 24));
        int r = clamp255(((c0 >> 16) & 0xff) + ((c1 >> 16) & 0xff) - ((c2 >> 16) & 0xff));
        int g = clamp255(((c0 >> 8) & 0xff) + ((c1 >> 8) & 0xff) - ((c2 >> 8) & 0xff));
        int b = clamp255((c0 & 0xff) + (c1 & 0xff) - (c2 & 0xff));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clampedAddSubtractHalf(int c0, int c1, int c2) {
        int average = average2(c0, c1);
        int a = addSubtractHalf(average >>> 24, c2 >>> 24);
        int r = addSubtractHalf((average >> 16) & 0xff, (c2 >> 16) & 0xff);
        int g = addSubtractHalf((average >> 8) & 0xff, (c2 >> 8) & 0xff);
        int b = addSubtractHalf(average & 0xff, c2 & 0xff);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int addSubtractHalf(int a, int b) {
        return clamp255(a + (a - b) / 2);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /* Writes a complete entropy coded image: no colour cache, no meta Huffman
     * codes, five Huffman trees and the LZ77 token stream. Only the top level
     * image carries the meta Huffman flag; the transform sub-images do not. */
    private static void writeImageStream(BitWriter bitWriter, int[] pixels, int width, int height, boolean topLevel) {
        Tokens tokens = compress(pixels, width * height);

        int[] greenHistogram = new int[GREEN_CODES];
        int[] redHistogram = new int[NUM_LITERAL_CODES];
        int[] blueHistogram = new int[NUM_LITERAL_CODES];
        int[] alphaHistogram = new int[NUM_LITERAL_CODES];
        int[] distanceHistogram = new int[NUM_DISTANCE_CODES];
        int[] prefix = new int[3];

        for (int i = 0; i < tokens.size; i++) {
            if (tokens.length[i] == 0) {
                int argb = tokens.value[i];
                greenHistogram[(argb >> 8) & 0xff]++;
                redHistogram[(argb >> 16) & 0xff]++;
                blueHistogram[argb & 0xff]++;
                alphaHistogram[argb >>> 24]++;
            } else {
                prefixEncode(tokens.length[i], prefix);
                greenHistogram[NUM_LITERAL_CODES + prefix[0]]++;
                prefixEncode(tokens.value[i] + PLANE_CODE_OFFSET, prefix);
                distanceHistogram[prefix[0]]++;
            }
        }

        bitWriter.putBits(0, 1);
        if (topLevel)
            bitWriter.putBits(0, 1);

        HuffmanCode green = HuffmanCode.build(greenHistogram);
        HuffmanCode red = HuffmanCode.build(redHistogram);
        HuffmanCode blue = HuffmanCode.build(blueHistogram);
        HuffmanCode alpha = HuffmanCode.build(alphaHistogram);
        HuffmanCode distance = HuffmanCode.build(distanceHistogram);

        green.write(bitWriter);
        red.write(bitWriter);
        blue.write(bitWriter);
        alpha.write(bitWriter);
        distance.write(bitWriter);

        for (int i = 0; i < tokens.size; i++) {
            if (tokens.length[i] == 0) {
                int argb = tokens.value[i];
                green.writeSymbol(bitWriter, (argb >> 8) & 0xff);
                red.writeSymbol(bitWriter, (argb >> 16) & 0xff);
                blue.writeSymbol(bitWriter, argb & 0xff);
                alpha.writeSymbol(bitWriter, argb >>> 24);
            } else {
                prefixEncode(tokens.length[i], prefix);
                green.writeSymbol(bitWriter, NUM_LITERAL_CODES + prefix[0]);
                bitWriter.putBits(prefix[2], prefix[1]);
                prefixEncode(tokens.value[i] + PLANE_CODE_OFFSET, prefix);
                distance.writeSymbol(bitWriter, prefix[0]);
                bitWriter.putBits(prefix[2], prefix[1]);
            }
        }
    }

    /* Length and distance values share the same prefix code: the four smallest
     * values are coded directly, larger ones as a bucket plus extra bits. */
    private static void prefixEncode(int value, int[] prefix) {
        if (value < 5) {
            prefix[0] = value - 1;
            prefix[1] = 0;
            prefix[2] = 0;
            return;
        }
        int shifted = value - 1;
        int highestBit = 31 - Integer.numberOfLeadingZeros(shifted);
        int extraBits = highestBit - 1;
        int secondBit = (shifted >> extraBits) & 1;
        prefix[0] = 2 * highestBit + secondBit;
        prefix[1] = extraBits;
        prefix[2] = shifted - ((2 + secondBit) << extraBits);
    }

    private static Tokens compress(int[] pixels, int count) {
        Tokens tokens = new Tokens(count);
        int[] head = new int[HASH_SIZE];
        Arrays.fill(head, -1);
        int[] previous = new int[count];
        int position = 0;

        while (position < count) {
            int bestLength = 0;
            int bestDistance = 0;

            if (position + MIN_MATCH <= count) {
                int maxLength = Math.min(MAX_MATCH, count - position);
                int candidate = head[hash(pixels, position)];
                int chain = MAX_CHAIN_LENGTH;

                while (candidate >= 0 && chain-- > 0) {
                    if (position - candidate > MAX_DISTANCE)
                        break;
                    if (pixels[candidate + bestLength] == pixels[position + bestLength] && pixels[candidate] == pixels[position]) {
                        int length = 0;
                        while (length < maxLength && pixels[candidate + length] == pixels[position + length])
                            length++;
                        if (length > bestLength) {
                            bestLength = length;
                            bestDistance = position - candidate;
                            if (bestLength >= maxLength)
                                break;
                        }
                    }
                    candidate = previous[candidate];
                }
            }

            if (bestLength >= MIN_MATCH) {
                tokens.add(bestLength, bestDistance);
                for (int i = 0; i < bestLength; i++)
                    insert(pixels, head, previous, position + i, count);
                position += bestLength;
            } else {
                tokens.add(0, pixels[position]);
                insert(pixels, head, previous, position, count);
                position++;
            }
        }
        return tokens;
    }

    private static void insert(int[] pixels, int[] head, int[] previous, int position, int count) {
        if (position + MIN_MATCH > count)
            return;
        int slot = hash(pixels, position);
        previous[position] = head[slot];
        head[slot] = position;
    }

    private static int hash(int[] pixels, int position) {
        int value = pixels[position] * 0x1e35a7bd + pixels[position + 1] * 0x2545f491 + pixels[position + 2] * 0x9e3779b1;
        return value >>> (32 - HASH_BITS);
    }

    private static final class Tokens {
        private int[] length;
        private int[] value;
        private int size;

        Tokens(int expectedCount) {
            int capacity = Math.max(16, Math.min(expectedCount, 1 << 20));
            length = new int[capacity];
            value = new int[capacity];
        }

        void add(int tokenLength, int tokenValue) {
            if (size == length.length) {
                length = Arrays.copyOf(length, size * 2);
                value = Arrays.copyOf(value, size * 2);
            }
            length[size] = tokenLength;
            value[size] = tokenValue;
            size++;
        }
    }

    private static final class HuffmanCode {
        private final int[] lengths;
        private final int[] codes;
        private final boolean simple;
        private final int usedCount;
        private final int firstSymbol;
        private final int secondSymbol;

        private HuffmanCode(int[] lengths, int[] codes, boolean simple, int usedCount, int firstSymbol, int secondSymbol) {
            this.lengths = lengths;
            this.codes = codes;
            this.simple = simple;
            this.usedCount = usedCount;
            this.firstSymbol = firstSymbol;
            this.secondSymbol = secondSymbol;
        }

        static HuffmanCode build(int[] histogram) {
            int used = 0;
            int first = 0;
            int second = 0;
            for (int i = 0; i < histogram.length; i++) {
                if (histogram[i] == 0)
                    continue;
                if (used == 0)
                    first = i;
                else if (used == 1)
                    second = i;
                used++;
            }

            /* Up to two symbols are stored as a "simple" code, which the decoder
             * reads without a code length table - and, for a single symbol,
             * without consuming any bit at all. */
            if (used <= 2 && first < NUM_LITERAL_CODES && second < NUM_LITERAL_CODES) {
                int[] lengths = new int[histogram.length];
                int[] codes = new int[histogram.length];
                if (used == 2) {
                    lengths[first] = 1;
                    lengths[second] = 1;
                    codes[second] = 1;
                }
                return new HuffmanCode(lengths, codes, true, used, first, second);
            }

            int[] frequencies = histogram.clone();
            if (used == 1)
                frequencies[first == 0 ? 1 : 0] = 1;
            int[] lengths = buildCodeLengths(frequencies, MAX_CODE_LENGTH);
            return new HuffmanCode(lengths, assignCodes(lengths), false, used, first, second);
        }

        void write(BitWriter bitWriter) {
            if (simple) {
                bitWriter.putBits(1, 1);
                bitWriter.putBits(usedCount == 2 ? 1 : 0, 1);
                if (firstSymbol < 2) {
                    bitWriter.putBits(0, 1);
                    bitWriter.putBits(firstSymbol, 1);
                } else {
                    bitWriter.putBits(1, 1);
                    bitWriter.putBits(firstSymbol, 8);
                }
                if (usedCount == 2)
                    bitWriter.putBits(secondSymbol, 8);
                return;
            }

            int[] symbols = new int[lengths.length * 2];
            int[] extraBits = new int[lengths.length * 2];
            int[] extraValues = new int[lengths.length * 2];
            int count = runLengthEncode(lengths, symbols, extraBits, extraValues);

            int[] histogram = new int[CODE_LENGTH_CODES];
            for (int i = 0; i < count; i++)
                histogram[symbols[i]]++;
            int usedCodeLengthCodes = 0;
            int firstUsed = 0;
            for (int i = 0; i < CODE_LENGTH_CODES; i++) {
                if (histogram[i] != 0) {
                    if (usedCodeLengthCodes == 0)
                        firstUsed = i;
                    usedCodeLengthCodes++;
                }
            }
            if (usedCodeLengthCodes == 1)
                histogram[firstUsed == 0 ? 1 : 0] = 1;

            int[] codeLengthLengths = buildCodeLengths(histogram, MAX_CODE_LENGTH_CODE_LENGTH);
            int[] codeLengthCodes = assignCodes(codeLengthLengths);

            int written = CODE_LENGTH_CODES;
            while (written > 4 && codeLengthLengths[CODE_LENGTH_ORDER[written - 1]] == 0)
                written--;

            bitWriter.putBits(0, 1);
            bitWriter.putBits(written - 4, 4);
            for (int i = 0; i < written; i++)
                bitWriter.putBits(codeLengthLengths[CODE_LENGTH_ORDER[i]], 3);
            bitWriter.putBits(0, 1);

            for (int i = 0; i < count; i++) {
                bitWriter.putBits(codeLengthCodes[symbols[i]], codeLengthLengths[symbols[i]]);
                if (extraBits[i] > 0)
                    bitWriter.putBits(extraValues[i], extraBits[i]);
            }
        }

        void writeSymbol(BitWriter bitWriter, int symbol) {
            bitWriter.putBits(codes[symbol], lengths[symbol]);
        }

        /* Codes 17 and 18 stand for runs of unused symbols, which keeps the
         * mostly empty 280 symbol alphabets small. */
        private static int runLengthEncode(int[] lengths, int[] symbols, int[] extraBits, int[] extraValues) {
            int count = 0;
            int index = 0;
            while (index < lengths.length) {
                int value = lengths[index];
                int run = 1;
                while (index + run < lengths.length && lengths[index + run] == value)
                    run++;
                if (value == 0) {
                    while (run >= 11) {
                        int emitted = Math.min(run, 138);
                        symbols[count] = 18;
                        extraBits[count] = 7;
                        extraValues[count] = emitted - 11;
                        count++;
                        index += emitted;
                        run -= emitted;
                    }
                    while (run >= 3) {
                        int emitted = Math.min(run, 10);
                        symbols[count] = 17;
                        extraBits[count] = 3;
                        extraValues[count] = emitted - 3;
                        count++;
                        index += emitted;
                        run -= emitted;
                    }
                }
                for (int i = 0; i < run; i++) {
                    symbols[count] = value;
                    extraBits[count] = 0;
                    extraValues[count] = 0;
                    count++;
                }
                index += run;
            }
            return count;
        }

        private static int[] buildCodeLengths(int[] histogram, int maxLength) {
            int[] frequencies = histogram.clone();
            while (true) {
                int[] lengths = huffmanLengths(frequencies);
                int longest = 0;
                for (int i = 0; i < lengths.length; i++)
                    longest = Math.max(longest, lengths[i]);
                if (longest <= maxLength)
                    return lengths;
                for (int i = 0; i < frequencies.length; i++)
                    if (frequencies[i] > 1)
                        frequencies[i] = (frequencies[i] + 1) >> 1;
            }
        }

        private static int[] huffmanLengths(int[] frequencies) {
            int symbolCount = frequencies.length;
            int nodeCount = 0;
            for (int i = 0; i < symbolCount; i++)
                if (frequencies[i] > 0)
                    nodeCount++;

            int[] lengths = new int[symbolCount];
            if (nodeCount == 0)
                return lengths;
            if (nodeCount == 1) {
                for (int i = 0; i < symbolCount; i++)
                    if (frequencies[i] > 0)
                        lengths[i] = 1;
                return lengths;
            }

            int maxNodes = 2 * nodeCount - 1;
            long[] nodeFrequency = new long[maxNodes];
            int[] left = new int[maxNodes];
            int[] right = new int[maxNodes];
            int[] symbol = new int[maxNodes];
            int[] heap = new int[maxNodes];
            int heapSize = 0;
            int nodes = 0;

            for (int i = 0; i < symbolCount; i++) {
                if (frequencies[i] == 0)
                    continue;
                nodeFrequency[nodes] = frequencies[i];
                left[nodes] = -1;
                right[nodes] = -1;
                symbol[nodes] = i;
                heap[heapSize++] = nodes;
                nodes++;
            }

            for (int i = heapSize / 2 - 1; i >= 0; i--)
                siftDown(heap, heapSize, i, nodeFrequency);

            while (heapSize > 1) {
                int a = heap[0];
                heap[0] = heap[--heapSize];
                siftDown(heap, heapSize, 0, nodeFrequency);
                int b = heap[0];
                nodeFrequency[nodes] = nodeFrequency[a] + nodeFrequency[b];
                left[nodes] = a;
                right[nodes] = b;
                symbol[nodes] = -1;
                heap[0] = nodes;
                nodes++;
                siftDown(heap, heapSize, 0, nodeFrequency);
            }

            int[] stackNode = new int[maxNodes];
            int[] stackDepth = new int[maxNodes];
            int stackSize = 0;
            stackNode[stackSize] = heap[0];
            stackDepth[stackSize] = 0;
            stackSize++;
            while (stackSize > 0) {
                stackSize--;
                int node = stackNode[stackSize];
                int depth = stackDepth[stackSize];
                if (symbol[node] >= 0) {
                    lengths[symbol[node]] = Math.max(depth, 1);
                    continue;
                }
                stackNode[stackSize] = left[node];
                stackDepth[stackSize] = depth + 1;
                stackSize++;
                stackNode[stackSize] = right[node];
                stackDepth[stackSize] = depth + 1;
                stackSize++;
            }
            return lengths;
        }

        private static void siftDown(int[] heap, int size, int index, long[] frequencies) {
            while (true) {
                int smallest = index;
                int leftChild = 2 * index + 1;
                int rightChild = leftChild + 1;
                if (leftChild < size && frequencies[heap[leftChild]] < frequencies[heap[smallest]])
                    smallest = leftChild;
                if (rightChild < size && frequencies[heap[rightChild]] < frequencies[heap[smallest]])
                    smallest = rightChild;
                if (smallest == index)
                    return;
                int swap = heap[index];
                heap[index] = heap[smallest];
                heap[smallest] = swap;
                index = smallest;
            }
        }

        /* Canonical codes, stored bit reversed because the decoder indexes its
         * lookup table with the bits in the order they arrive. */
        private static int[] assignCodes(int[] lengths) {
            int[] countPerLength = new int[MAX_CODE_LENGTH + 1];
            for (int i = 0; i < lengths.length; i++)
                countPerLength[lengths[i]]++;
            countPerLength[0] = 0;

            int[] nextCode = new int[MAX_CODE_LENGTH + 2];
            int code = 0;
            for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
                code = (code + countPerLength[length - 1]) << 1;
                nextCode[length] = code;
            }

            int[] codes = new int[lengths.length];
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0)
                    codes[i] = reverseBits(nextCode[lengths[i]]++, lengths[i]);
            }
            return codes;
        }

        private static int reverseBits(int value, int length) {
            int reversed = 0;
            for (int i = 0; i < length; i++)
                reversed |= ((value >> i) & 1) << (length - 1 - i);
            return reversed;
        }
    }

    private static final class BitWriter {
        private byte[] buffer;
        private int size;
        private long accumulator;
        private int accumulatedBits;

        BitWriter(int expectedSize) {
            buffer = new byte[Math.max(64, Math.min(expectedSize, 1 << 22))];
        }

        void putBits(int value, int count) {
            if (count == 0)
                return;
            accumulator |= (((long)value) & ((1L << count) - 1)) << accumulatedBits;
            accumulatedBits += count;
            while (accumulatedBits >= 8) {
                append((byte)(accumulator & 0xff));
                accumulator >>>= 8;
                accumulatedBits -= 8;
            }
        }

        private void append(byte value) {
            if (size == buffer.length)
                buffer = Arrays.copyOf(buffer, size * 2);
            buffer[size++] = value;
        }

        byte[] toByteArray() {
            if (accumulatedBits > 0) {
                append((byte)(accumulator & 0xff));
                accumulator = 0;
                accumulatedBits = 0;
            }
            return Arrays.copyOf(buffer, size);
        }
    }
}
