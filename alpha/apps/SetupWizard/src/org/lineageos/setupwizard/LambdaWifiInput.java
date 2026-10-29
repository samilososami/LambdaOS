/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.setupwizard;

/** Pure validation, unit tested without Android. Never logs or persists input. */
final class LambdaWifiInput {
    static boolean validPassword(String value,boolean sae){
        if(value==null||value.indexOf('\0')>=0)return false;
        int bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(sae)return bytes>=1&&bytes<=63;
        return (bytes>=8&&bytes<=63)||value.matches("[0-9a-fA-F]{64}");
    }
    static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
