package com.molido.vpn;

import org.json.JSONArray;
import org.json.JSONObject;

public final class FragmentMasks {
    private FragmentMasks() {}

    public static boolean valid(String finalMask) {
        try {
            JSONObject root = new JSONObject(finalMask);
            JSONArray tcp = root.optJSONArray("tcp");
            return tcp == null || valid(tcp);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean valid(JSONArray masks) {
        for (int i = 0; i < masks.length(); i++) {
            JSONObject mask = masks.optJSONObject(i);
            if (mask == null) return false;
            if (!"fragment".equals(mask.optString("type"))) continue;
            JSONObject settings = mask.optJSONObject("settings");
            JSONArray lengths = settings == null ? null : settings.optJSONArray("lengths");
            if (lengths == null || lengths.length() == 0) return false;
            for (int j = 0; j < lengths.length(); j++) {
                String[] range = lengths.optString(j).split("-", -1);
                if (range.length < 1 || range.length > 2) return false;
                try {
                    int min = Integer.parseInt(range[0]);
                    int max = range.length == 2 ? Integer.parseInt(range[1]) : min;
                    if (min <= 0 || max < min) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return true;
    }
}
