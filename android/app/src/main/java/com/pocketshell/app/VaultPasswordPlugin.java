package com.pocketshell.app;

import android.app.Activity;
import android.os.CancellationSignal;
import androidx.credentials.CreatePasswordRequest;
import androidx.credentials.CreateCredentialResponse;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPasswordOption;
import androidx.credentials.PasswordCredential;
import androidx.credentials.exceptions.CreateCredentialException;
import androidx.credentials.exceptions.GetCredentialException;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Saves and fetches the account vault passphrase through the system password
 * manager (Google Password Manager) with Android Credential Manager. Both
 * calls are explicit user actions that show the system's own sheet.
 *
 * <p>JS: {@code save({accountId, passphrase}) -> {outcome}} and
 * {@code retrieve({accountId}) -> {outcome, passphrase?}}. The passphrase
 * travels only between the WebView bridge and the system provider: it is
 * dropped from the call data once read, never logged, never sent over the
 * network and never placed on the clipboard.
 */
@CapacitorPlugin(name = "VaultPassword")
public final class VaultPasswordPlugin extends Plugin {
    private final Executor callbacks = Executors.newSingleThreadExecutor();

    @PluginMethod
    public void save(PluginCall call) {
        String accountId = call.getString("accountId");
        String passphrase = call.getString("passphrase");
        call.getData().remove("passphrase");
        Activity host = getActivity();
        String id;
        try {
            id = VaultPasswordOutcomes.credentialId(accountId);
        } catch (IllegalArgumentException invalid) {
            call.reject("A verified account is required.", "VAULT_PASSWORD_ACCOUNT");
            return;
        }
        if (passphrase == null || passphrase.isEmpty() || host == null || host.isFinishing()) {
            call.resolve(outcome(VaultPasswordOutcomes.FAILED));
            return;
        }
        CredentialManager.create(host).createCredentialAsync(host, new CreatePasswordRequest(id, passphrase),
                new CancellationSignal(), callbacks,
                new CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>() {
                    @Override
                    public void onResult(CreateCredentialResponse response) {
                        call.resolve(outcome(VaultPasswordOutcomes.SAVED));
                    }

                    @Override
                    public void onError(CreateCredentialException error) {
                        call.resolve(outcome(VaultPasswordOutcomes.saveFailure(error)));
                    }
                });
    }

    @PluginMethod
    public void retrieve(PluginCall call) {
        String accountId = call.getString("accountId");
        Activity host = getActivity();
        String id;
        try {
            id = VaultPasswordOutcomes.credentialId(accountId);
        } catch (IllegalArgumentException invalid) {
            call.reject("A verified account is required.", "VAULT_PASSWORD_ACCOUNT");
            return;
        }
        if (host == null || host.isFinishing()) {
            call.resolve(outcome(VaultPasswordOutcomes.FAILED));
            return;
        }
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(new GetPasswordOption(Collections.singleton(id)))
                .build();
        CredentialManager.create(host).getCredentialAsync(host, request, new CancellationSignal(), callbacks,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        if (!(response.getCredential() instanceof PasswordCredential)) {
                            call.resolve(outcome(VaultPasswordOutcomes.FAILED));
                            return;
                        }
                        PasswordCredential credential = (PasswordCredential) response.getCredential();
                        if (!VaultPasswordOutcomes.belongsTo(credential.getId(), accountId)) {
                            call.resolve(outcome(VaultPasswordOutcomes.OTHER_ACCOUNT));
                            return;
                        }
                        call.resolve(outcome("ok").put("passphrase", credential.getPassword()));
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        call.resolve(outcome(VaultPasswordOutcomes.retrieveFailure(error)));
                    }
                });
    }

    private static JSObject outcome(String value) {
        return new JSObject().put("outcome", value);
    }
}
