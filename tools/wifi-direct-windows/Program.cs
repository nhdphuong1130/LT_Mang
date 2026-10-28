using System.Text.Json;

#if WINDOWS
using Windows.Devices.Enumeration;
using Windows.Devices.WiFiDirect;
#endif

namespace WifiDirectAgentHelper;

public sealed record DirectLinkEvent(string Status, string PeerName, string? DeviceId, string? Message)
{
    public string ToJson() => JsonSerializer.Serialize(this, JsonOptions);
    private static readonly JsonSerializerOptions JsonOptions = new() {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };
}

public static class DirectLinkState
{
    public static DirectLinkEvent Searching(string peer) => new("SEARCHING", peer, null, null);
    public static DirectLinkEvent Discovered(string peer, string id) => new("DISCOVERED", peer, id, null);
    public static DirectLinkEvent Listening() => new("LISTENING", "", null, "Wi-Fi Direct listener active; waiting for incoming connection");
    public static DirectLinkEvent PublisherStarting() => new("PUBLISHER_STARTING", "", null,
        "Starting Wi-Fi Direct advertisement publisher");
    public static DirectLinkEvent PublisherStarted() => Listening();
    public static DirectLinkEvent PublisherAborted(string reason) => new("PUBLISHER_ABORTED", "", null,
        "Wi-Fi Direct advertisement publisher aborted: " + reason);
    public static DirectLinkEvent ConnectionRequested(string peer, string id) =>
        new("CONNECTION_REQUESTED", peer, id, "Incoming Wi-Fi Direct connection request");
    public static DirectLinkEvent PairingStarted(string peer, string id, string method) =>
        new("PAIRING", peer, id, "Pairing requested with method: " + method);
    public static DirectLinkEvent PairingSucceeded(string peer, string id, string result) =>
        new("PAIRED", peer, id, "Pairing result: " + result);
    public static DirectLinkEvent PairingFailed(string peer, string id, string result) =>
        new("PAIRING_FAILED", peer, id, "Pairing result: " + result);
    public static DirectLinkEvent OpeningDevice(string peer, string id) =>
        new("OPENING_DEVICE", peer, id, "Opening negotiated Wi-Fi Direct device");
    public static DirectLinkEvent Unavailable(string peer, string message) => new("UNAVAILABLE", peer, null, message);
    public static DirectLinkEvent Connected(string peer, string id) => new("CONNECTED", peer, id, null);
    public static DirectLinkEvent Disconnected(string peer, string id) => new("RECONNECTING", peer, id, "Wi-Fi Direct link disconnected");
}

public static class PairingPolicy
{
    public static bool MayOpenDevice(string result) =>
        result is "Paired" or "AlreadyPaired";
}

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        if (args.Contains("--help") || args.Contains("-h")) {
            Console.WriteLine("Usage:");
            Console.WriteLine("  WifiDirectAgentHelper.exe [--listen]");
            Console.WriteLine("  WifiDirectAgentHelper.exe --peer-name <Linux peer name>");
            Console.WriteLine("  WifiDirectAgentHelper.exe --list");
            return 0;
        }
        if (args.Contains("--list")) {
#if WINDOWS
            return await ListWindowsPeersAsync();
#else
            Emit(DirectLinkState.Unavailable("", "Wi-Fi Direct WinRT API is available only on Windows"));
            return 3;
#endif
        }
        string? peerName = Argument(args, "--peer-name");
        if (!string.IsNullOrWhiteSpace(peerName)) {
            Emit(DirectLinkState.Searching(peerName));
#if WINDOWS
            return await RunWindowsAsync(peerName);
#else
            Emit(DirectLinkState.Unavailable(peerName, "Wi-Fi Direct WinRT API is available only on Windows"));
            return 3;
#endif
        }
#if WINDOWS
        return await RunListenerAsync();
#else
        Emit(DirectLinkState.Unavailable("", "Wi-Fi Direct WinRT API is available only on Windows"));
        return 3;
#endif
    }

    private static string? Argument(string[] args, string name)
    {
        for (int i = 0; i + 1 < args.Length; i++) if (args[i] == name) return args[i + 1];
        return null;
    }

    private static void Emit(DirectLinkEvent value) => Console.Out.WriteLine(value.ToJson());

#if WINDOWS
    private static async Task<int> ListWindowsPeersAsync()
    {
        var publisher = new WiFiDirectAdvertisementPublisher();
        publisher.Advertisement.ListenStateDiscoverability = WiFiDirectAdvertisementListenStateDiscoverability.Normal;
        AttachPublisherDiagnostics(publisher);
        Emit(DirectLinkState.PublisherStarting());
        try {
            publisher.Start();
        } catch (Exception failure) {
            Emit(DirectLinkState.PublisherAborted(failure.GetType().Name + ": " + failure.Message));
            return 1;
        }
        try {
            await Task.Delay(2000);
            string selector = WiFiDirectDevice.GetDeviceSelector(WiFiDirectDeviceSelectorType.AssociationEndpoint);
            DeviceInformationCollection devices = await DeviceInformation.FindAllAsync(selector);
            foreach (DeviceInformation peer in devices) Emit(DirectLinkState.Discovered(peer.Name, peer.Id));
            if (devices.Count == 0) Emit(DirectLinkState.Unavailable("", "No discoverable Wi-Fi Direct peer"));
            return devices.Count == 0 ? 4 : 0;
        } finally {
            try { publisher.Stop(); } catch { }
        }
    }

    private static async Task<int> RunListenerAsync()
    {
        try {
            var publisher = new WiFiDirectAdvertisementPublisher();
            publisher.Advertisement.ListenStateDiscoverability = WiFiDirectAdvertisementListenStateDiscoverability.Normal;
            publisher.Advertisement.IsAutonomousGroupOwnerEnabled = false;
            AttachPublisherDiagnostics(publisher);
            Emit(DirectLinkState.PublisherStarting());
            publisher.Start();

            var listener = new WiFiDirectConnectionListener();
            var stopped = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            WiFiDirectDevice? activeDevice = null;

            listener.ConnectionRequested += async (sender, args) => {
                try {
                    WiFiDirectConnectionRequest request = args.GetConnectionRequest();
                    var device = request.DeviceInformation;
                    Emit(DirectLinkState.ConnectionRequested(device.Name, device.Id));

                    if (device.Pairing.CanPair && !device.Pairing.IsPaired) {
                        var custom = device.Pairing.Custom;
                        custom.PairingRequested += (pairingSender, pairingArgs) => {
                            Emit(DirectLinkState.PairingStarted(device.Name, device.Id,
                                pairingArgs.PairingKind.ToString()));
                            pairingArgs.Accept();
                        };
                        Emit(DirectLinkState.PairingStarted(device.Name, device.Id,
                            "ConfirmOnly|ProvidePin|DisplayPin"));
                        DevicePairingResult pairing = await custom.PairAsync(
                            DevicePairingKinds.ConfirmOnly | DevicePairingKinds.ProvidePin | DevicePairingKinds.DisplayPin,
                            DevicePairingProtectionLevel.None);
                        string pairingStatus = pairing.Status.ToString();
                        if (!PairingPolicy.MayOpenDevice(pairingStatus)) {
                            Emit(DirectLinkState.PairingFailed(device.Name, device.Id, pairingStatus));
                            return;
                        }
                        Emit(DirectLinkState.PairingSucceeded(device.Name, device.Id, pairingStatus));
                    } else if (device.Pairing.IsPaired) {
                        Emit(DirectLinkState.PairingSucceeded(device.Name, device.Id, "AlreadyPaired"));
                    } else {
                        Emit(DirectLinkState.PairingFailed(device.Name, device.Id, "PairingUnavailable"));
                        return;
                    }

                    var parameters = new WiFiDirectConnectionParameters { GroupOwnerIntent = 0 };
                    Emit(DirectLinkState.OpeningDevice(device.Name, device.Id));
                    activeDevice = await WiFiDirectDevice.FromIdAsync(device.Id, parameters);
                    if (activeDevice == null) {
                        Emit(DirectLinkState.Unavailable(device.Name,
                            "Windows returned no Wi-Fi Direct device after pairing"));
                        return;
                    }
                    Emit(DirectLinkState.Connected(device.Name, device.Id));
                    activeDevice.ConnectionStatusChanged += (_, _) => {
                        if (activeDevice.ConnectionStatus == WiFiDirectConnectionStatus.Connected) {
                            Emit(DirectLinkState.Connected(device.Name, device.Id));
                        } else {
                            Emit(DirectLinkState.Disconnected(device.Name, device.Id));
                        }
                    };
                } catch (Exception ex) {
                    Emit(DirectLinkState.Unavailable("", ex.GetType().Name + ": Connection error: " + ex.Message));
                }
            };

            Console.CancelKeyPress += (_, eventArgs) => {
                eventArgs.Cancel = true;
                try { publisher.Stop(); } catch { }
                activeDevice?.Dispose();
                stopped.TrySetResult();
            };

            await stopped.Task;
            return 0;
        } catch (Exception failure) {
            Emit(DirectLinkState.Unavailable("", failure.GetType().Name + ": " + failure.Message));
            return 1;
        }
    }

    private static void AttachPublisherDiagnostics(WiFiDirectAdvertisementPublisher publisher)
    {
        WiFiDirectAdvertisementPublisherStatus? reported = null;
        void ReportStatus(WiFiDirectAdvertisementPublisherStatus status, WiFiDirectError error)
        {
            lock (publisher) {
                if (reported == status) return;
                reported = status;
            }
            if (status == WiFiDirectAdvertisementPublisherStatus.Started) {
                Emit(DirectLinkState.PublisherStarted());
            } else if (status == WiFiDirectAdvertisementPublisherStatus.Aborted) {
                Emit(DirectLinkState.PublisherAborted(error.ToString()));
            }
        }
        publisher.StatusChanged += (_, args) => ReportStatus(args.Status, args.Error);
    }

    private static async Task<int> RunWindowsAsync(string peerName)
    {
        try {
            string selector = WiFiDirectDevice.GetDeviceSelector(WiFiDirectDeviceSelectorType.AssociationEndpoint);
            DeviceInformationCollection devices = await DeviceInformation.FindAllAsync(selector);
            DeviceInformation? peer = devices.FirstOrDefault(value =>
                    value.Name.Contains(peerName, StringComparison.OrdinalIgnoreCase));
            if (peer is null) {
                Emit(DirectLinkState.Unavailable(peerName, "Configured Linux Wi-Fi Direct peer was not discovered"));
                return 4;
            }
            if (peer.Pairing.CanPair && !peer.Pairing.IsPaired) {
                DevicePairingResult pairing = await peer.Pairing.PairAsync(DevicePairingProtectionLevel.None);
                if (pairing.Status is not DevicePairingResultStatus.Paired
                        and not DevicePairingResultStatus.AlreadyPaired) {
                    Emit(DirectLinkState.Unavailable(peerName, $"Pairing failed: {pairing.Status}"));
                    return 5;
                }
            }
            WiFiDirectDevice? direct = await WiFiDirectDevice.FromIdAsync(peer.Id);
            if (direct is null) {
                Emit(DirectLinkState.Unavailable(peerName, "Windows could not open the paired Wi-Fi Direct device"));
                return 6;
            }
            using (direct) {
                Emit(DirectLinkState.Connected(peer.Name, peer.Id));
                direct.ConnectionStatusChanged += (_, _) => Emit(
                        direct.ConnectionStatus == WiFiDirectConnectionStatus.Connected
                            ? DirectLinkState.Connected(peer.Name, peer.Id)
                            : DirectLinkState.Disconnected(peer.Name, peer.Id));
                var stopped = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                Console.CancelKeyPress += (_, eventArgs) => { eventArgs.Cancel = true; stopped.TrySetResult(); };
                await stopped.Task;
            }
            return 0;
        } catch (Exception failure) {
            Emit(DirectLinkState.Unavailable(peerName, failure.GetType().Name + ": " + failure.Message));
            return 1;
        }
    }
#endif
}
