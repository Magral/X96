// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;
import java.util.Set;
interface RecoveryStore {
    Set<String> disabled(String device);
    boolean disabled(String device,Set<String> packages);
    String home(String device);
    boolean home(String device,String component);
    boolean forgetHome(String device);
    String option(String device,String id);
    boolean option(String device,String id,String snapshot);
    boolean forgetOption(String device,String id);
}
