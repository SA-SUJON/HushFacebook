/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Process;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSigningInfo;

import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Who a patched Facebook lets reach a guarded component as a same-key family app. The certificate
 * makes the caller safe: only someone holding the user's Manager key can sign an app to it, so a
 * caller carrying this build's own key is one the user built. The family name keeps the widening to
 * the apps Facebook treats as family, so a lookalike name is still refused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FamilySignatureTrustTest {
    /** This build's key, and a stranger's. Any distinct certificates will do. */
    private static final String OUR_KEY = "308201a0b1c2d3";
    private static final String OTHER_KEY = "3040e5f6a7b8c9";

    private static final String MESSENGER = "com.facebook.orca";
    private static final int CALLER_UID = 12345;
    private static final int STRANGER_UID = 54321;

    private Context context;
    private ShadowPackageManager packages;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packages = shadowOf(context.getPackageManager());
        install(context.getPackageName(), Process.myUid(), OUR_KEY);
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        ShadowBinder.reset();
        HookStatus.clear();
    }

    /**
     * Installs [packageName] signed with [certificate]. Only the signing info is filled in, as
     * PackageManager fills it for GET_SIGNING_CERTIFICATES on API 28 and up, so the old signatures
     * array can't stand in for it.
     */
    private void install(String packageName, int uid, String certificate) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        SigningInfo signing = new SigningInfo();
        ((ShadowSigningInfo) Shadow.extract(signing)).setSignatures(new Signature[]{new Signature(certificate)});
        info.signingInfo = signing;
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = packageName;
        app.uid = uid;
        info.applicationInfo = app;
        packages.installPackage(info);
    }

    /** Installs a caller under its own uid and makes it the one now calling the provider. */
    private void caller(String packageName, String certificate) {
        install(packageName, CALLER_UID, certificate);
        packages.setPackagesForUid(CALLER_UID, packageName);
        ShadowBinder.setCallingUid(CALLER_UID);
    }

    @Test
    public void aSameKeyMessengerIsAccepted() {
        caller(MESSENGER, OUR_KEY);
        assertTrue("a same-key Messenger should reach the guarded component",
                FamilySignatureTrust.accept(context));
    }

    /** The whole family, each carrying this build's key, gets in the same way. */
    @Test
    public void everySameKeyFamilyAppIsAccepted() {
        for (String pkg : FamilySignatureTrust.FAMILY_PACKAGES) {
            ShadowBinder.reset();
            packages.removePackage(pkg);
            caller(pkg, OUR_KEY);
            assertTrue(pkg + " carries this build's key", FamilySignatureTrust.accept(context));
        }
    }

    /** A caller signed with another key is what Facebook's own check already refuses. */
    @Test
    public void aForeignSignerIsRefused() {
        caller(MESSENGER, OTHER_KEY);
        assertFalse("a Messenger signed with another key must not get in",
                FamilySignatureTrust.accept(context));
    }

    /** The family names are matched exactly, so a name that only looks like one is refused. */
    @Test
    public void aLookalikePackageNameIsRefused() {
        for (String lookalike : new String[]{
                "com.facebook.orca.evil", "com.facebook.orcax", "com.facebook0orca", "com.facebook.katana.morphe"}) {
            ShadowBinder.reset();
            packages.removePackage(lookalike);
            caller(lookalike, OUR_KEY);
            assertFalse(lookalike + " is not a family app", FamilySignatureTrust.accept(context));
        }
    }

    /** A uid with no package behind it, as an unknown or gone caller leaves, keeps the refusal. */
    @Test
    public void anUnknownCallerIsRefused() {
        ShadowBinder.setCallingUid(STRANGER_UID);
        assertFalse("a caller with no package must not get in", FamilySignatureTrust.accept(context));
    }

    /** Without a context the check can read nothing, so it refuses. */
    @Test
    public void aNullContextIsRefused() {
        caller(MESSENGER, OUR_KEY);
        assertFalse(FamilySignatureTrust.accept(null));
    }

    /**
     * Facebook's own check accepts a caller Meta signed with the same key Facebook carries. On an
     * unmodified install that key is Meta's, and this method places the same trust: a family app
     * carrying the running build's own key, whatever that key is, is accepted.
     */
    @Test
    public void aFamilyAppSharingTheRunningBuildsKeyIsAccepted() {
        // Model Meta's own install: the app and Messenger share one key.
        install(context.getPackageName(), Process.myUid(), OTHER_KEY);
        caller(MESSENGER, OTHER_KEY);
        assertTrue("a family app that shares the running build's key is trusted",
                FamilySignatureTrust.accept(context));
    }

    /** Every call is counted under the coexistence patch's name. */
    @Test
    public void callsAreCountedUnderThePatch() {
        caller(MESSENGER, OUR_KEY);
        FamilySignatureTrust.accept(context);
        boolean reported = false;
        for (String line : HookStatus.report("")) {
            if (line.startsWith("Install beside Meta's apps:")) reported = true;
        }
        assertTrue("the hook reports under the coexistence patch", reported);
    }
}
