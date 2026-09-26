package com.smartcollab.global.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "198.51.100.7, 198.51.100.7",
            "198.51.100.7:51234, 198.51.100.7",
            "'[2001:db8::1]:443', 2001:db8::1",
            "'[2001:db8::1]', 2001:db8::1",
            "2001:db8::1, 2001:db8::1",
            "0:0:0:0:0:0:0:1, 0:0:0:0:0:0:0:1",
            "' 198.51.100.7 ', 198.51.100.7",
            "'', unknown",
    })
    void normalizesPortsAndBrackets(String raw, String expected) {
        assertThat(ClientIp.normalize(raw)).isEqualTo(expected);
    }
}
