package app.skyalert.core;

import java.util.ArrayList;
import java.util.List;

/** An aircraft inside the radar, enriched with Skycards data. */
public final class Sighting {
    public String hex = "";
    public String code = "";
    public String name = "";
    public Double rarity;          // null when Skycards does not know the type
    public String category = "unknown";
    public int xp;
    public boolean excluded;
    public double distKm;
    public String compass = "";
    public boolean onGround;
    public Integer altFt;
    public Double groundSpeedKt;
    public String reg = "";
    public String callsign = "";
    public boolean military;
    public boolean hidden;
    public boolean uncaught;
    public List<String> reasons = new ArrayList<>();
}
