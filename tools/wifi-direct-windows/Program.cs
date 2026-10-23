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
    public static DirectLinkEvent Unavailable(string peer, string message) => new("UNAVAILABLE", peer, null, message);
    public static DirectLinkEvent Connected(string peer, string id) => new("CONNECTED", peer, id, null);
    public static DirectLinkEvent Disconnected(string peer, string id) => new("RECONNECTING", peer, id, "Wi-Fi Direct link disconnected");
}

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        if (args.Contains("--list")) {
#if WINDOWS
            return await ListWindowsPeersAsync();
#else
            Emit(DirectLinkState.Unavailable("", "Wi-Fi Direct WinRT API is available only on Windows"));
            return 3;
#endif
        }
        string? peerName = Argument(args, "--peer-name");
        if (string.IsNullOrWhiteSpace(peerName)) {
            Console.Error.WriteLine("Usage: WifiDirectAgentHelper.exe --peer-name <Linux peer name>");
            return 2;
        }
        Emit(DirectLinkState.Searching(peerName));
#if WINDOWS
        return await RunWindowsAsync(peerName);
#else
        Emit(DirectLinkState.Unavailable(peerName, "Wi-Fi Direct WinRT API is available only on Windows"));
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
        string selector = WiFiDirectDevice.GetDeviceSelector(WiFiDirectDeviceSelectorType.AssociationEndpoint);
        DeviceInformationCollection devices = await DeviceInformation.FindAllAsync(selector);
        foreach (DeviceInformation peer in devices) Emit(DirectLinkState.Discovered(peer.Name, peer.Id));
        if (devices.Count == 0) Emit(DirectLinkState.Unavailable("", "No discoverable Wi-Fi Direct peer"));
        return devices.Count == 0 ? 4 : 0;
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
