package dev.tactical.profile;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Plain data also used by the config editor; never changes the item's components. */
public record ItemProfile(Key key,int width,int height,Rarity rarity) {
    public static final int MAX_SIZE=6, MAX_RULES=2048;
    public ItemProfile {
        Objects.requireNonNull(key); Objects.requireNonNull(rarity);
        if(width<1 || height<1 || width>MAX_SIZE || height>MAX_SIZE) throw new IllegalArgumentException("占格宽高必须为 1–6");
    }
    public record Key(String item,String content) {
        private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
        public Key {
            if(item==null || !ID.matcher(item).matches() || item.length()>256 || content==null
                    || content.length()>256 || !content.isEmpty() && !ID.matcher(content).matches())
                throw new IllegalArgumentException("无效的物品 / GWO 型号 ID");
        }
        public String encoded() { return item+(content.isEmpty()?"":"@"+content); }
        public Key base() { return new Key(item,""); }
    }
    public static ItemProfile resolve(Map<Key,ItemProfile> profiles,Key key) {
        var exact=profiles.get(key);
        return exact!=null?exact:profiles.get(key.base());
    }
}
