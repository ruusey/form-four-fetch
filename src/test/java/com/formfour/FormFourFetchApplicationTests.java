package com.formfour;

import org.junit.jupiter.api.Test;

import com.formfour.service.FormFourService;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormFourFetchApplicationTests {

    @Test
    void accessionDashFormatting() {
        assertEquals("0001234567-25-001234", FormFourService.withDashes("000123456725001234"));
        assertEquals("000123456725001234", FormFourService.stripDashes("0001234567-25-001234"));
    }
}
