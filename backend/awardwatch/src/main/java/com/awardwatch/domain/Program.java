package com.awardwatch.domain;

public enum Program {
    EUROBONUS("SAS EuroBonus"),
    FLYING_CLUB("Virgin Atlantic Flying Club"),
    CLUB_PREMIER("Aeromexico Club Premier"),
    AADVANTAGE("American Airlines AAdvantage"),
    SKYMILES("Delta SkyMiles"),
    ETIHAD_GUEST("Etihad Guest"),
    UNITED_MILEAGEPLUS("United MileagePlus"),
    SKYWARDS("Emirates Skywards"),
    AEROPLAN("Air Canada Aeroplan"),
    ALASK_MILEAGE_PLAN("Alaska Mileage Plan"),
    VELOCITY("Virgin Australia Velocity"),
    QANTAS_FREQUENT_FLYER("Qantas Frequent Flyer"),
    CONNECTMILES("Copa ConnectMiles"),
    TUDOAZUL("Azul TudoAzul"),
    GOL_SMILES("GOL Smiles"),
    FLYING_BLUE("Air France/KLM Flying Blue"),
    TRUEBLUE("jetBlue TrueBlue"),
    PRIVILEGE_CLUB("Quatar Privilege Clube"),
    MILES_AND_SMILES("Turkish Miles&Smiles"),
    KRISFLYER("Singapor KirsFlyer"),
    SHEBAMILES("Ethiopian ShebaMiles"),
    ALFURSAN("Saudia AlFursan"),
    FINNAIR_PLUS("Finnair Plus"),
    MILES_AND_MORE("Lufthansa Miles&More"),
    FRONTIER_MILES("Frontier Miles"),
    FREE_SPIRIT("Spirit Free Spirit");

    private final String displayName;

    Program(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

}
