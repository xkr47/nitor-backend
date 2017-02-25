/**
 * Copyright 2017 Nitor Creations Oy
 *
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
package io.nitor.api.backend.session;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static java.lang.ThreadLocal.withInitial;
import static java.util.Arrays.copyOf;
import static java.util.zip.Deflater.BEST_COMPRESSION;

public class Compressor {
    private static final ThreadLocal<Deflater> DEFLATER = withInitial(() -> new Deflater(BEST_COMPRESSION, true));
    private static final ThreadLocal<Inflater> INFLATER = withInitial(() -> new Inflater(true));

    public static byte[] compress(byte[] input) {
        Deflater compressor = DEFLATER.get();
        compressor.reset();
        compressor.setInput(input);
        compressor.finish();
        // worst case compression is 14% expansion (http://www.zlib.net/zlib_tech.html) -> calculate using 20%
        // + 3 byte minimal overhead in java implementation
        byte[] output = new byte[input.length + input.length / 5 + 3];
        int compressedLength = compressor.deflate(output);
        if (!compressor.finished()) {
            throw new RuntimeException("Unexpectedly poorly compressing data");
        }
        return copyOf(output, compressedLength);
    }

    public static byte[] decompress(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        Inflater decompresser = INFLATER.get();
        decompresser.reset();
        decompresser.setInput(compressed);
        int pos = 0;
        try {
            while (true) {
                int dataLength = decompresser.inflate(output, pos, output.length - pos);
                pos += dataLength;
                if (decompresser.finished()) {
                    return copyOf(output, pos);
                }
                output = copyOf(output, output.length * 2);
            }
        } catch (DataFormatException e) {
            throw new RuntimeException(e);
        }
    }

}
