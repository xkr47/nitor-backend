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

import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import static io.nitor.api.backend.s3.AWSUrlEncoder.uriEncode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AWSUrlEncoderTest {

    @Test
    public void testUrlEncoder() throws UnsupportedEncodingException {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<65535; ++i) {
            if (i >= 0xd800 && i <= 0xDFFF) {
                continue;
            }
            String ch = Character.valueOf((char) i).toString();
            sb.setLength(0);
            String own = uriEncode(ch, false, sb);
            String java = URLEncoder.encode(ch, "UTF-8");
            switch (ch) {
                case " ": assertThat(own, is("%20")); break;
                case "*": assertThat(own, is("%2A")); break;
                case "/": assertThat(own, is("/")); break;
                case "~": assertThat(own, is("~")); break;
                default: assertThat("Converting " + ch + "(0x" + Integer.toHexString(i) + ")", own, is(java));
            }
        }
    }
}