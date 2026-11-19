package com.x.backendvoip.user;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.x.backendvoip.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

public final class PhoneNumbers {
    private static final String DEFAULT_REGION = "VN";
    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();
    private static final Pattern DIAL_STRING = Pattern.compile("\\+?[0-9]{3,15}");

    private PhoneNumbers() {}

    public record Phone(String e164, boolean vietnamLandlineOrMobile) {}

    public static Phone parse(String input) {
        if (input == null || input.length() > 40) throw invalid();
        String compact = input.replaceAll("[\\p{Zs}()\\-]", "");
        if (!DIAL_STRING.matcher(compact).matches()) throw invalid();
        try {
            var number = UTIL.parse(compact, DEFAULT_REGION);
            if (!UTIL.isValidNumber(number)) throw invalid();
            var type = UTIL.getNumberType(number);
            boolean domestic = DEFAULT_REGION.equals(UTIL.getRegionCodeForNumber(number));
            boolean landlineOrMobile = switch (type) {
                case FIXED_LINE, MOBILE, FIXED_LINE_OR_MOBILE -> true;
                default -> false;
            };
            return new Phone(UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164), domestic && landlineOrMobile);
        } catch (NumberParseException error) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER", "Enter a valid telephone number.");
    }
}
