package com.pocketshell.app;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(SshCapabilityPlugin.class);
        registerPlugin(KeyboardInsetsPlugin.class);
        registerPlugin(InstalledDataMigrationPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
