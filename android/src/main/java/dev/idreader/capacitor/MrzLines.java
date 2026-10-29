package dev.idreader.capacitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds TD1, TD2 and TD3 access fields and rejects failed check digits. */
final class MrzLines {
    private MrzLines() {}

    static String[] find(List<String> raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            String normalized = line.toUpperCase(Locale.ROOT).replace("«", "<<").replace("‹", "<").replaceAll("[^A-Z0-9<]", "");
            if (!normalized.isEmpty()) lines.add(normalized);
        }
        for (int length : new int[]{30, 36, 44}) {
            List<String> candidates = new ArrayList<>();
            for (String line : lines) if (Math.abs(line.length() - length) <= 2) candidates.add(line);
            for (int i = 0; i + 1 < candidates.size(); i++) {
                String first = candidates.get(i), second = candidates.get(i + 1);
                if ("PIAC".indexOf(first.charAt(0)) < 0) continue;
                String numberLine = length == 30 ? first : second;
                int numberStart = length == 30 ? 5 : 0;
                int birthStart = length == 30 ? 0 : 13;
                int expiryStart = length == 30 ? 8 : 21;
                if (numberLine.length() < numberStart + 10 || second.length() < expiryStart + 7) continue;
                String number = numberLine.substring(numberStart, numberStart + 9);
                String birth = digits(second.substring(birthStart, birthStart + 6));
                String expiry = digits(second.substring(expiryStart, expiryStart + 6));
                if (!birth.matches("[0-9]{6}") || !expiry.matches("[0-9]{6}") || number.replace("<", "").isEmpty()) continue;
                if (!check(number, digits(numberLine.substring(numberStart + 9, numberStart + 10))) ||
                    !check(birth, digits(second.substring(birthStart + 6, birthStart + 7))) ||
                    !check(expiry, digits(second.substring(expiryStart + 6, expiryStart + 7)))) continue;
                return new String[]{first, second};
            }
        }
        return null;
    }

    private static String digits(String input) {
        return input.replace('O', '0').replace('Q', '0').replace('D', '0').replace('I', '1')
            .replace('L', '1').replace('Z', '2').replace('S', '5').replace('B', '8').replace('G', '6');
    }

    private static boolean check(String input, String expected) {
        if (!expected.matches("[0-9]")) return false;
        int[] weights = {7, 3, 1};
        int sum = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int value = c == '<' ? 0 : c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'Z' ? c - 'A' + 10 : -1;
            if (value < 0) return false;
            sum += value * weights[i % 3];
        }
        return sum % 10 == expected.charAt(0) - '0';
    }
}
