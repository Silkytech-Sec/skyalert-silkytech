package app.skyalert.core;

/** One Skycards aircraft model (card). */
public final class Model {
    public final String code;
    public final String name;
    public final double rarity;
    public final String category;
    public final int xp;
    public final boolean excluded;

    public Model(String code, String name, double rarity, String category, int xp, boolean excluded) {
        this.code = code;
        this.name = name;
        this.rarity = rarity;
        this.category = category;
        this.xp = xp;
        this.excluded = excluded;
    }
}
