/*
 * Eturlia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Eturlia contributors
 */

package eturlia.launcher;

/**
 * Dependency-free checks for launcher logic that can run without a built server jar.
 *
 * <p>Currently covers {@link Main#compareVersions(String, String)}, which used to be a plain
 * {@code String.compareTo} and therefore ranked {@code commons-lang-2.6} above
 * {@code commons-lang-2.10} when picking the newest embedded library.</p>
 *
 * <p>Run via {@code scripts/selftest.sh}. Exit code 0 means every check passed.</p>
 */
public final class EturliaLauncherSelfTest {

    private static int checks;
    private static int failures;

    private EturliaLauncherSelfTest() {}

    public static void main(String[] args) {
        greater("2.10 beats 2.6", "commons-lang-2.10.jar", "commons-lang-2.6.jar");
        greater("21.1.248 beats 21.1.9", "neoforge-21.1.248.jar", "neoforge-21.1.9.jar");
        greater("longer version wins on tie prefix", "lib-1.2.1.jar", "lib-1.2.jar");
        greater("ordering prefix is ignored", "072-jopt-simple-5.0.4.jar", "071-jopt-simple-5.0.3.jar");
        equalRank("identical names", "spark-1.10.53.jar", "spark-1.10.53.jar");
        greater("versioned beats unversioned", "lib-1.0.jar", "lib.jar");

        System.out.println();
        System.out.println(failures == 0
                ? "OK — " + checks + " checks passed"
                : "FAILED — " + failures + " of " + checks + " checks failed");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void greater(String label, String left, String right) {
        checks++;
        int forward = Main.compareVersions(left, right);
        int backward = Main.compareVersions(right, left);
        if (forward > 0 && backward < 0) {
            System.out.println("pass  " + label);
        } else {
            failures++;
            System.out.println("FAIL  " + label + " — compare(" + left + ", " + right + ")="
                    + forward + ", reverse=" + backward);
        }
    }

    private static void equalRank(String label, String left, String right) {
        checks++;
        if (Main.compareVersions(left, right) == 0) {
            System.out.println("pass  " + label);
        } else {
            failures++;
            System.out.println("FAIL  " + label);
        }
    }
}
