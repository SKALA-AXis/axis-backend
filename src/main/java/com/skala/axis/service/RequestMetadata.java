package com.skala.axis.service;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

public record RequestMetadata(String ipAddress, String userAgent, String countryCode, String countryName) {
    public static RequestMetadata from(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = forwardedFor == null || forwardedFor.isBlank()
                ? request.getRemoteAddr()
                : forwardedFor.split(",")[0].trim();
        String userAgent = request.getHeader("User-Agent");
        String countryCode = normalizeCountryCode(firstPresentHeader(
                request,
                "CF-IPCountry",
                "CloudFront-Viewer-Country",
                "X-Vercel-IP-Country",
                "X-AppEngine-Country",
                "X-Country-Code"
        ));
        return new RequestMetadata(ip, userAgent, countryCode, inferCountryName(ip, countryCode));
    }

    public static String inferCountryName(String ipAddress, String countryCode) {
        if (isLoopbackAddress(ipAddress)) {
            return "로컬 개발환경";
        }
        if (isPrivateAddress(ipAddress)) {
            return "사내/내부망";
        }
        String normalizedCode = normalizeCountryCode(countryCode);
        if (normalizedCode == null) {
            return "알 수 없음";
        }
        Locale countryLocale = new Locale("", normalizedCode);
        String displayCountry = countryLocale.getDisplayCountry(Locale.KOREAN);
        return displayCountry == null || displayCountry.isBlank() || normalizedCode.equals(displayCountry)
                ? normalizedCode
                : displayCountry;
    }

    private static String firstPresentHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeCountryCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String countryCode = value.trim().toUpperCase(Locale.ROOT);
        if (countryCode.length() != 2 || !countryCode.chars().allMatch(Character::isLetter) || "XX".equals(countryCode)) {
            return null;
        }
        return countryCode;
    }

    private static boolean isLoopbackAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String ip = value.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(ip)
                || "127.0.0.1".equals(ip)
                || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip);
    }

    private static boolean isPrivateAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String ip = value.trim().toLowerCase(Locale.ROOT);
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length > 1) {
                try {
                    int secondOctet = Integer.parseInt(parts[1]);
                    return secondOctet >= 16 && secondOctet <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return ip.startsWith("fc") || ip.startsWith("fd") || ip.startsWith("fe80:");
    }
}
