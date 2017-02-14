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

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.nitor.api.backend.session.Compressor.compress;
import static io.nitor.api.backend.session.Compressor.decompress;
import static java.util.Arrays.fill;
import static java.util.stream.IntStream.concat;
import static java.util.stream.IntStream.of;
import static java.util.stream.IntStream.range;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CompressorTest {
    private Supplier<IntStream> testedContentLengths = () ->
            concat(range(0, 20), of(50, 100, 1000));

    @Test
    public void compressBestCaseArrays() {
        testedContentLengths.get().forEach(len -> {
            byte[] a = new byte[len];
            fill(a, (byte) 'a');
            assertRoundTrip(a);
        });
    }

    @Test
    public void compressWorstCaseArrays() {
        Random r = new Random();
        testedContentLengths.get().forEach(len -> {
            byte[] a = new byte[len];
            r.nextBytes(a);
            assertRoundTrip(a);
        });
    }

    private void assertRoundTrip(byte[] data) {
        assertArrayEquals(data, decompress(compress(data)));
    }
}