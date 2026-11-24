package local.headunit.diplay;

import android.app.Application;

/** Installs process-wide connection-crash evidence before any component starts. */
public final class DiPlayApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        CrashEvidence.initialize(this);
        if (HotspotEvidence.supported()) HotspotEvidence.initialize(this);
    }
}
