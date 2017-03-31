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
package io.nitor.api.backend.s3;

public class AWSUrlEncoder {
    private static final char[] hexChars = new char[]{'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
    private static final byte[] urlEncodedByte = new byte[256*2];
    private static final boolean[] urlEncodeNeeded = new boolean[128];
    private static final boolean[] urlEncodeNeededWithSlash;

    static {
        for (int i=0; i<256; ++i) {
            urlEncodedByte[i*2] = (byte) hexChars[i >> 4];
            urlEncodedByte[i*2+1] = (byte) hexChars[i & 15];
            if (i >= 128) {
                continue;
            }
            if ((i >= 'A' && i <= 'Z') || (i >= 'a' && i <= 'z') || (i >= '0' && i <= '9') || i == '_' || i == '-' || i == '~' || i == '.' || i == '/') {
                continue;
            }
            urlEncodeNeeded[i] = true;
        }
        urlEncodeNeededWithSlash = urlEncodeNeeded.clone();
        urlEncodeNeededWithSlash['/'] = false;
    }

    public static String uriEncode(CharSequence input, boolean encodeSlash, StringBuilder result) {
        boolean[] urlEncodeAscii = encodeSlash ? urlEncodeNeededWithSlash : urlEncodeNeeded;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch <= 0x7F) {
                if (urlEncodeAscii[ch]) {
                    result.append('%');
                    int idx = ch * 2;
                    result.append((char) urlEncodedByte[idx]);
                    result.append((char) urlEncodedByte[idx+1]);
                } else {
                    result.append(ch);
                }
            } else if (ch <= 0x7FF) {
                result.append('%');
                int idx = (0xc0 | (ch >> 6)) * 2;
                result.append((char) urlEncodedByte[idx]);
                result.append((char) urlEncodedByte[idx+1]);
                result.append('%');
                idx = (0x80 | (ch & 0x3F)) * 2;
                result.append((char) urlEncodedByte[idx]);
                result.append((char) urlEncodedByte[idx+1]);
            } else {
                result.append('%');
                int idx = (0xe0 | (ch >> 12)) * 2;
                result.append((char) urlEncodedByte[idx]);
                result.append((char) urlEncodedByte[idx+1]);
                result.append('%');
                idx = (0x80 | ((ch >> 6) & 0x3F)) * 2;
                result.append((char) urlEncodedByte[idx]);
                result.append((char) urlEncodedByte[idx+1]);
                result.append('%');
                idx = (0x80 | (ch & 0x3F)) * 2;
                result.append((char) urlEncodedByte[idx]);
                result.append((char) urlEncodedByte[idx+1]);
            }
        }
        return result.toString();
    }

}
