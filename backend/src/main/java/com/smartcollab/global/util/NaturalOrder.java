package com.smartcollab.global.util;

import java.math.BigInteger;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * 한국어 자연 정렬 — 화면이 쓰던 {@code Intl.Collator('ko', { numeric: true, sensitivity: 'base' })} 와 같은 규칙.
 * 숫자 덩어리는 수로 비교하고("보고서 2" &lt; "보고서 10"), 나머지는 대소문자·악센트를 구분하지 않는 한국어 순서로 비교합니다.
 * 폴더 목록을 서버가 나눠 주면서 정렬도 서버가 정하게 되어 옮겨 왔습니다 [IMP-02].
 */
public final class NaturalOrder implements Comparator<String> {

    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private static final ThreadLocal<Collator> COLLATOR = ThreadLocal.withInitial(() -> {
        Collator c = Collator.getInstance(Locale.KOREAN);
        c.setStrength(Collator.PRIMARY);
        return c;
    });

    private NaturalOrder() {
    }

    @Override
    public int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            boolean digitA = Character.isDigit(a.charAt(i));
            boolean digitB = Character.isDigit(b.charAt(j));
            int endA = runEnd(a, i, digitA);
            int endB = runEnd(b, j, digitB);
            int cmp;
            if (digitA && digitB) {
                cmp = new BigInteger(a.substring(i, endA)).compareTo(new BigInteger(b.substring(j, endB)));
            } else if (digitA != digitB) {
                cmp = digitA ? -1 : 1;   // 숫자가 글자보다 앞
            } else {
                cmp = COLLATOR.get().compare(a.substring(i, endA), b.substring(j, endB));
            }
            if (cmp != 0) return cmp;
            i = endA;
            j = endB;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int runEnd(String s, int from, boolean digits) {
        int k = from;
        while (k < s.length() && Character.isDigit(s.charAt(k)) == digits) k++;
        return k;
    }
}
