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

public class ByteHelpers {
    public static void write16(byte[] output, int pos, int value) {
        output[pos] = (byte) (value & 0xFF);
        output[pos+1] = (byte) ((value >>> 8) & 0xFF);
    }

    public static void write32(byte[] output, int pos, int value) {
        output[pos] = (byte) (value & 0xFF);
        output[pos+1] = (byte) ((value >>> 8) & 0xFF);
        output[pos+2] = (byte) ((value >>> 16) & 0xFF);
        output[pos+3] = (byte) ((value >>> 24) & 0xFF);
    }

    public static int read16(byte[] output, int pos) {
        int value = output[pos] & 0xFF;
        value |= (output[pos+1] & 0xFF) << 8;
        return value;
    }

    public static int read32(byte[] output, int pos) {
        int value = output[pos] & 0xFF;
        value |= (output[pos+1] & 0xFF) << 8;
        value |= (output[pos+2] & 0xFF) << 16;
        value |= (output[pos+3] & 0xFF) << 24;
        return value;
    }
}
