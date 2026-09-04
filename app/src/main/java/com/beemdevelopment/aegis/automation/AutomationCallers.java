package com.beemdevelopment.aegis.automation;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Build;

import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fork (白い熊 防具): who is allowed through the automation data door, and how that is decided.
 *
 * <p>Ported verbatim in behaviour from the family reference
 * ({@code shiroikuma-jiyusagyoban} → {@code core/automation/AutomationCallers.kt}); this file is
 * deliberately app-independent, so the pins below are the family's, not this fork's.
 *
 * <h3>Why not a token</h3>
 * The token this replaces was a 48-character secret 白い熊 pasted from one app's settings into
 * another's. It cannot survive a wipe, which is fatal for the case the whole family now exists to
 * serve: 応用管理 restoring apps and their data onto a clean phone, where nothing is configured yet.
 *
 * <h3>Why not a {@code shiroikuma.*} prefix</h3>
 * Because that is not an identity. What makes {@code getCallingPackage()} worth anything is that a
 * package name <b>cannot be taken while the real package is installed</b> — package names are not a
 * namespace anyone owns, so any sideloaded app may call itself {@code shiroikuma.evil} and pass a
 * prefix test. Since the caller supplies the file descriptor an export is written into, a prefix
 * check would hand such an app the complete data of every sister app in turn: strictly weaker than
 * the token it replaces.
 *
 * <h3>What is actually checked, in order</h3>
 * <ol>
 *   <li><b>An exact name</b> from {@link #CALLERS}. Two callers exist and both are known here; a
 *       third is a one-line change.</li>
 *   <li><b>The uid agrees.</b> {@code getCallingPackage()} reflects the caller's <i>declared</i>
 *       attribution, and packages sharing a uid are not distinguished by it, so it is confirmed
 *       against the uid the kernel reports — that answer cannot be borrowed.</li>
 *   <li><b>The signing certificate matches a pinned hash.</b> This is the one that closes the real
 *       gap: <i>whichever caller package is absent from the device is a name anyone can take</i>,
 *       and the clean-phone case this contract exists for is precisely a device where not
 *       everything is installed yet — the moment the assumption is weakest is the moment it is most
 *       needed.</li>
 * </ol>
 *
 * <p>This check runs <b>whether or not</b> a token is being asked for; it is not the token's
 * replacement in the sense of an either/or. See {@link com.beemdevelopment.aegis.helpers.AutomationAuth}
 * for the app's own switches, which are consulted after this one.
 */
public final class AutomationCallers {

    /**
     * The apps allowed to drive this one's data door.
     *
     * <p>応用管理 backs up and restores; 自由作業盤 runs the 保存復元 batch. Nothing else has any
     * business exporting this app's data, and an entry added here is a deliberate act.
     */
    private static final Map<String, String> CALLERS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("shiroikuma.oyokanri", "9c585f4d118cb97ff653f949a8872875548403b9083ce6b9baa2e8f0c55ac6cc");
        m.put("shiroikuma.jiyusagyoban", "efd0d352192651593a92288ecdc64fc87262ec8648c24ed8f51a5587d46ac602");
        CALLERS = Collections.unmodifiableMap(m);
    }

    /**
     * Where those hashes come from, so the next person can re-derive them rather than trust them:
     *
     * <pre>apksigner verify --print-certs &lt;the app's signed release APK&gt; | grep 'SHA-256 digest'</pre>
     *
     * <p>Every app in the family has <b>its own keystore</b> — 42 of them under
     * {@code ~/.android-keystores/} — so there is no shared signing key to compare against and each
     * caller must be pinned by name. That is also why a {@code protectionLevel="signature"}
     * permission was never an option here.
     *
     * <p><b>If a caller's key is ever rotated, its calls stop working and the fix is these
     * constants.</b> That is the intended failure: a signing key changing without anyone noticing is
     * exactly what a pin exists to catch.
     */
    private static final String HOW_TO_DERIVE_PINS = "apksigner verify --print-certs <apk>";

    private AutomationCallers() { }

    /**
     * The caller check: {@code null} means allowed, anything else is the exact {@code ERROR:} line
     * to answer with.
     *
     * <p>It answers a string rather than a boolean because a refusal that says only "no" is a
     * refusal nobody can debug from the other side of an IPC boundary. Each of these is a different
     * mistake with a different fix, and the caller shows them to 白い熊 verbatim. It is the same
     * shape as {@link com.beemdevelopment.aegis.helpers.AutomationAuth#refuse} on purpose, so the
     * provider reads as one gate in two halves.
     */
    @Nullable
    public static String refuse(Context context, @Nullable String declared) {
        if (declared == null || declared.isEmpty()) {
            return "ERROR:caller unknown";
        }
        String pin = CALLERS.get(declared);
        if (pin == null) {
            return "ERROR:caller not permitted: " + declared;
        }

        // The kernel's answer, not the caller's. A package may declare an attribution it does not
        // own; the uid cannot be borrowed.
        String[] real;
        try {
            real = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        } catch (Exception e) {
            real = null;
        }
        List<String> owned = real == null ? Collections.emptyList() : Arrays.asList(real);
        if (!owned.contains(declared)) {
            return "ERROR:caller uid mismatch: " + declared;
        }

        String signature = signingSha256(context, declared);
        if (signature == null) {
            return "ERROR:caller signature unreadable: " + declared;
        }
        // Constant-time, like the token compare it sits beside — the value is a public hash, but the
        // habit is worth keeping and costs nothing.
        if (!MessageDigest.isEqual(signature.getBytes(), pin.getBytes())) {
            return "ERROR:caller signature mismatch: " + declared;
        }
        return null;
    }

    /**
     * The SHA-256 of the caller's current signing certificate, lower-case hex.
     *
     * <p>{@code signingInfo} rather than the deprecated {@code signatures}: a rotated key reports its
     * whole history and we want the certificate actually in force. On API 23–27 that API does not
     * exist — the flag is accepted but {@code signingInfo} comes back null — and <b>without the
     * fallback branch the door would refuse every caller</b> on those devices. That failure would
     * never appear on 白い熊's phone (API 31) and would only surface on an older one. The deprecated
     * array is the correct answer there, not a compromise: before key rotation existed,
     * {@code signatures} <i>was</i> the signing certificate. This app's minSdk is 23, so the branch
     * is live, not theoretical.
     *
     * <p>Exactly one signer, or we decline to guess: "several signers, one of which matches" is a
     * question about key rotation that nothing in this family needs to answer — every app here has
     * one key and has never rotated it.
     */
    @Nullable
    private static String signingSha256(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] certs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SigningInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                certs = info == null ? null : info.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                Signature[] legacy = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
                certs = legacy;
            }
            if (certs == null || certs.length != 1) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
