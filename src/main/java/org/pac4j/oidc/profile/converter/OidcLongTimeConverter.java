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
package org.pac4j.oidc.profile.converter;

import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.profile.FormattedDate;
import org.pac4j.core.profile.converter.AttributeConverter;
import org.pac4j.core.profile.converter.Converters;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Convert a number of seconds into a Date.
 *
 * @author Jerome Leleu
 * @since 1.9.2
 */
public class OidcLongTimeConverter implements AttributeConverter<Date> {

    @Override
    public Date convert(final Object attribute) {
        if (attribute instanceof Long) {
            final long seconds = (Long) attribute;
            return new FormattedDate(new Date(seconds * 1000), Converters.DATE_TZ_GENERAL_FORMAT, Locale.getDefault());
        } else if (attribute instanceof String) {
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            try {
                return new FormattedDate(sdf.parse((String) attribute), "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());
            } catch (final ParseException e) {
                throw new TechnicalException(e);
            }
        } else if (attribute instanceof FormattedDate) {
            return (Date) attribute;
        }
        return null;
    }
}
