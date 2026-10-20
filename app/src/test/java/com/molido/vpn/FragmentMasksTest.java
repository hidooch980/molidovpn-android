package com.molido.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class FragmentMasksTest {
    private JSONArray masks(String... sizes) {
        return new JSONArray().put(new JSONObject().put("type", "fragment")
            .put("settings", new JSONObject().put("lengths", new JSONArray(sizes))
                .put("delays", new JSONArray().put("0"))));
    }

    @Test public void zeroFromPublisherIsRejected() {
        assertFalse(FragmentMasks.valid(masks("0", "104", "1")));
        assertFalse(FragmentMasks.valid(masks("0-5")));
    }

    @Test public void positiveSizesAndZeroDelayArePreserved() {
        JSONArray valid = masks("5", "94", "1-3");
        String before = valid.toString();
        assertTrue(FragmentMasks.valid(valid));
        assertEquals(before, valid.toString());
    }

    @Test public void malformedRangesAreRejected() {
        for (String size : new String[]{"", "-1", "4-2", "1-2-3", "x", "2147483648"})
            assertFalse(size, FragmentMasks.valid(masks(size)));
    }

    @Test public void badMaskCannotHideBehindGoodOne() {
        JSONArray batch = masks("1");
        batch.put(masks("0").getJSONObject(0));
        assertFalse(FragmentMasks.valid(batch));
    }

    @Test public void wrapperAndUnrelatedMasksAreHandled() {
        assertTrue(FragmentMasks.valid("{}"));
        assertTrue(FragmentMasks.valid(new JSONArray().put(new JSONObject().put("type", "other"))));
        assertFalse(FragmentMasks.valid("broken"));
        assertFalse(FragmentMasks.valid(new JSONObject().put("tcp", masks("0")).toString()));
    }
}
