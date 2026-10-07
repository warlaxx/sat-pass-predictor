package space.nextpass.gcat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link GcatObject#isSeparation(String)} on real GCAT rows, read on 5 October 2026 (ABD-12):
 * the object's type, launch date, parent and separation date, then the parent's type and
 * its own parent. An empty parent type is a parent outside the satellite catalogue.
 */
class SeparationRuleTest {

    @ParameterizedTest(name = "{0} {1}: {8}")
    @CsvSource(delimiter = '|', nullValues = "", value = {
            // jcat  | name                    | type         | launch       | parent  | separation           | parent type  | its parent | separation?
            // A deployer emptying, on the launch day: a tug's cubesat, a host's subsatellites.
            "S47430  | Astrocast-0101          | P        k   | 2021 Jan 24  | S47486  | 2021 Jan 24 1813     | P        c   | S47435     | true",
            "S39432  | ICUBE-1                 | P            | 2013 Nov 21  | S39421  | 2013 Nov 21 0810?    | P            | S39450     | true",
            "S20481  | Kosmos-2059 SS 1        | PX-C---      | 1990 Feb  6  | S20476  | 1990 Feb  6          | P            | S20477     | true",
            // Carriers that never leave the stage, and Starship, a stage GCAT counts as a payload:
            // the launch itself.
            "S100855 | Starlink 40083          | P      A  M  | 2026 Sep 28  | S100881 | 2026 Sep 28 1324:04  | P            | R86825     | false",
            "S47545  | Flock 4s-23             | P        g   | 2021 Jan 24  | S47435  | 2021 Jan 24 1604     | PXAA--U  k   | S47437     | false",
            "S40977  | SINOD-D-3               | P ------c    | 2015 Oct  8  | S40978  | 2015 Oct  8 1617?    | PXA---U-C    | A08415     | false",
            "S40042  | PolyITAN 1              | P            | 2014 Jun 19  | S40049  | 2014 Jun 19 1927     | C  A         | S40047     | false",
            // A stage delivering past midnight UTC: still the launch.
            "S39613  | Ekspress-AT2            | P   G        | 2014 Mar 15  | S39614  | 2014 Mar 16 0829     | R4  G        | A08088     | false",
            "S21140  | Meteosat 5              | P   G        | 1991 Mar  2  | S21141  | 1991 Mar  3 0000     | R3           | R58841     | false",
            "S11384  | Molniya-3               | P            | 1979 Jun  5  | S11554  | 1979 Jun  6 0034     | R4           | S11386     | false",
            "S12603  | DM-20L SOZ-1            | C  M         | 1981 Jun 25  | S12851  | 1981 Jun 26 0628?    | R4  G        | S12565     | false",
            // Launch hardware releasing long after the launch: a real event.
            "S20291  | Kosmos-1985 SS 26       | PX-C---      | 1988 Dec 23  | S19764  | 1989 Oct 14          | R3           | R56226     | true",
            "S25819  | AS-4 debris             | C  X         | 1994 May  4  | S23100  | 1999 May?            | R4           | R61395     | true",
            "S22031  | KDU part                | C  A         | 1992 Jun 23  | S22030  | 1992 Jul  9 0353?    | C  M         | S21998     | true",
            // A parent in the auxiliary catalogue: days later, a deployer; the same day, the stage.
            "S100802 | Torga                   | P       ?    | 2026 Jul  7  | A11919  | 2026 Sep 22 1000?    |              |            | true",
            "S69998  | Starlink 38086          | P      A  M  | 2026 Jul 11  | A11921  | 2026 Jul 11 0402:25  |              |            | false",
            // What GCAT itself marks spurious.
            "S100681 | CSSHQ subsat duplicate  | Z  X         | 2026 Feb  7  | S67689  | 2026 Sep 10?         | P            | S67690     | false",
            "S25591  | deb USA 74-77           | Z  X    c    | 1991 Nov  8  | S21799  | 1998 Dec 24?         | P       c    | A05059     | false",
            // Spacecraft shedding parts or breaking up, after the launch day, vague dates included.
            "S59993  | deb Meteor-2?           | D  B         | 1981 May 14  | S12456  | 2024?                | P            | S12457     | true",
            "S16013  | EVA screwdriver         | C -H-S-      | 1985 Aug 27  | S15992  | 1985 Sep  2 1300?    | PH H-S       | A04119     | true",
            "S69328  | Shenzhou 22 Guidao Cang | CP T T       | 2025 Nov 25  | S66645  | 2026 May 29 1120     | PH-H-V-      | S66646     | true",
            // Debris on the launch day: deployment hardware as a rule; this one, a breakup, is missed.
            "S03600  | deb Kosmos-249          | D  W         | 1968 Oct 20  | S03504  | 1968 Oct 20 1427     | P            | S03505     | false",
    })
    void decidesRealCases(String jcat, String name, String type, String launch, String parent,
                          String separation, String parentType, String grandparent, boolean expected) {
        GcatObject object = object(jcat, type, launch, parent, separation);

        assertThat(object.isSeparation(parentType == null ? null : new GcatObject.Parent(parentType, grandparent))).isEqualTo(expected);
    }

    @Test
    void neverWithoutAParentOrADateOrFromALaunchVehicle() {
        assertThat(object("S1", "P", "2026 Jul  7", null, "2026 Sep 22").isSeparation(null)).isFalse();
        assertThat(object("S1", "P", "2026 Jul  7", "S2", null).isSeparation(spacecraft())).isFalse();
        // S100961, Falcon Heavy-014 Stage 2: its parent is in the launch vehicle database.
        assertThat(object("S100961", "R2", "2026 Oct  2", "R86832", "2026 Oct  2 0402").isSeparation(null)).isFalse();
    }

    /**
     * ABD-13: A11846, a Dragon trunk dropped a month after its launch, passes the rule; the
     * auxiliary catalogue is imported for the lineage, and is not listed until ABD-51 says so.
     */
    @Test
    void anAuxiliaryCatalogueRowIsNeverListed() {
        assertThat(object("A11846", "C  A T", "2026 May 15", "S69103", "2026 Jun 17 1136?").isSeparation(spacecraft()))
                .isFalse();
        assertThat(object("S11846", "C  A T", "2026 May 15", "S69103", "2026 Jun 17 1136?").isSeparation(spacecraft()))
                .isTrue();
        assertThat(GcatObject.listed("S100399")).isTrue();
        assertThat(GcatObject.listed("A11695")).isFalse();
        assertThat(GcatObject.listed(null)).isFalse();
    }

    @Test
    void anAliasIsNotASecondSeparation() {
        assertThat(object("S1", "PA", "2020 Jan  1", "S2", "2021 Jan  1").isSeparation(spacecraft())).isFalse();
    }

    @Test
    void aVagueDateCountsOnlyWhenItsEarliestReadingIsLateEnough() {
        // "2026 Jul?" may be the launch day: from a stage, it is not a separation yet.
        assertThat(object("S1", "P", "2026 Jul  7", "S2", "2026 Jul?").isSeparation(stage())).isFalse();
        assertThat(object("S1", "D", "2026 Jul  7", "S2", "2026 Jul?").isSeparation(spacecraft())).isFalse();
        assertThat(object("S1", "D", "2026 Jul  7", "S2", "2026 Aug?").isSeparation(spacecraft())).isTrue();
    }

    @Test
    void twoDaysAfterTheLaunchAStageReleaseIsNoLongerTheLaunch() {
        assertThat(object("S1", "P", "2026 Jul  7 1000", "S2", "2026 Jul  9 0959").isSeparation(stage())).isFalse();
        assertThat(object("S1", "P", "2026 Jul  7 1000", "S2", "2026 Jul  9 1000").isSeparation(stage())).isTrue();
    }

    @Test
    void withoutALaunchDateTheParentDecides() {
        assertThat(object("S1", "P", null, "S2", "1999").isSeparation(spacecraft())).isTrue();
    }

    @Test
    void aVehicleHangingOnALaunchVehicleEntryIsPartOfTheLaunch() {
        assertThat(new GcatObject.Parent("P", "R86825").isLaunchVehicle()).isTrue();
        assertThat(new GcatObject.Parent("P        c", "S47435").isLaunchVehicle()).isFalse();
        assertThat(new GcatObject.Parent("P", null).isLaunchVehicle()).isFalse();
    }

    @Test
    void launchHardwareIsReadFromTheTypeCharacters() {
        assertThat(GcatObject.isLaunchHardware("R3")).isTrue();
        assertThat(GcatObject.isLaunchHardware("C  A")).isTrue();
        assertThat(GcatObject.isLaunchHardware("C  F")).isTrue();
        assertThat(GcatObject.isLaunchHardware("C  M")).isTrue();
        assertThat(GcatObject.isLaunchHardware("C  V")).isTrue();
        assertThat(GcatObject.isLaunchHardware("PXA---U-C")).isTrue();
        assertThat(GcatObject.isLaunchHardware("P        c")).isFalse();
        assertThat(GcatObject.isLaunchHardware("CP T T")).isFalse();
        assertThat(GcatObject.isLaunchHardware("PH H-S")).isFalse();
        assertThat(GcatObject.isLaunchHardware("P")).isFalse();
    }

    private static GcatObject.Parent spacecraft() {
        return new GcatObject.Parent("P", "S9");
    }

    private static GcatObject.Parent stage() {
        return new GcatObject.Parent("R2", null);
    }

    private static GcatObject object(String jcat, String type, String launch, String parent, String separation) {
        return new GcatObject(jcat, null, null, null, type, jcat, null,
                launch, GcatDate.parse(launch), parent, parent, separation, GcatDate.parse(separation),
                "Earth", null, null, "O", null, null, null, null, null, null, null, null);
    }
}
