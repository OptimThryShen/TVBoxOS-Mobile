package com.github.tvbox.osc.util;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class PlayerHelperTest {
    private JSONObject config(int player) throws Exception {
        return new JSONObject().put("pl", player).put("pr", 1)
                .put("ijk", "硬解码").put("sc", 2).put("sp", 1.5);
    }

    @Test
    public void tsUsesExoRegardlessOfPreferredPlayer() throws Exception {
        for (int player = 0; player <= 2; player++) {
            JSONObject original = config(player);
            JSONObject local = PlayerHelper.getLocalPlayerCfg(original, "/Download/视频 #1.ts");
            assertEquals(2, local.getInt("pl"));
            assertEquals(player, original.getInt("pl"));
            assertEquals(1, local.getInt("pr"));
            assertEquals(2, local.getInt("sc"));
            assertEquals(1.5, local.getDouble("sp"), 0);
            assertEquals("硬解码", local.getString("ijk"));
        }
    }

    @Test
    public void extensionMatchingIsCaseInsensitiveAndLocaleIndependent() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(2, PlayerHelper.getLocalPlayerCfg(config(1), "/Download/VIDEO.TS").getInt("pl"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void nonTsEpisodeKeepsPreferredPlayerAfterTsEpisode() throws Exception {
        JSONObject original = config(1);
        JSONObject ts = PlayerHelper.getLocalPlayerCfg(original, "/Download/episode.ts");
        ts.put("sc", 0);
        for (String path : new String[]{"/Download/next.mp4", "/Download/next.mkv",
                "/Download/next.ts.mp4", "/Download/list.m3u8", "", null}) {
            JSONObject next = PlayerHelper.getLocalPlayerCfg(original, path);
            assertNotSame(original, next);
            assertEquals(1, next.getInt("pl"));
            assertEquals(2, next.getInt("sc"));
        }
    }
}
