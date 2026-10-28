using System.Text.Json;
using WifiDirectAgentHelper;
using Xunit;

namespace WifiDirectAgentHelper.Tests;

public sealed class DirectLinkStateTests
{
    [Fact]
    public void ConnectedStateUsesStableLineDelimitedJsonShape()
    {
        using JsonDocument json = JsonDocument.Parse(DirectLinkState.Connected("LTM Server", "peer-1").ToJson());
        Assert.Equal("CONNECTED", json.RootElement.GetProperty("status").GetString());
        Assert.Equal("LTM Server", json.RootElement.GetProperty("peerName").GetString());
        Assert.Equal("peer-1", json.RootElement.GetProperty("deviceId").GetString());
    }

    [Fact]
    public void ListeningStateUsesStableLineDelimitedJsonShape()
    {
        using JsonDocument json = JsonDocument.Parse(DirectLinkState.Listening().ToJson());
        Assert.Equal("LISTENING", json.RootElement.GetProperty("status").GetString());
        Assert.NotNull(json.RootElement.GetProperty("message").GetString());
    }

    [Fact]
    public void DisconnectTransitionsToReconnectState()
    {
        DirectLinkEvent state = DirectLinkState.Disconnected("LTM Server", "peer-1");
        Assert.Equal("RECONNECTING", state.Status);
        Assert.NotNull(state.Message);
    }

    [Fact]
    public void PublisherLifecycleDistinguishesStartingStartedAndAborted()
    {
        Assert.Equal("PUBLISHER_STARTING", DirectLinkState.PublisherStarting().Status);
        Assert.Equal("LISTENING", DirectLinkState.PublisherStarted().Status);

        DirectLinkEvent aborted = DirectLinkState.PublisherAborted("RadioNotAvailable");
        Assert.Equal("PUBLISHER_ABORTED", aborted.Status);
        Assert.Contains("RadioNotAvailable", aborted.Message);
    }

    [Fact]
    public void IncomingRequestIncludesPeerIdentity()
    {
        DirectLinkEvent state = DirectLinkState.ConnectionRequested("Windows peer", "device-7");
        Assert.Equal("CONNECTION_REQUESTED", state.Status);
        Assert.Equal("Windows peer", state.PeerName);
        Assert.Equal("device-7", state.DeviceId);
    }

    [Fact]
    public void PairingLifecycleReportsMethodAndTerminalResult()
    {
        DirectLinkEvent started = DirectLinkState.PairingStarted("peer", "id", "ConfirmOnly");
        Assert.Equal("PAIRING", started.Status);
        Assert.Contains("ConfirmOnly", started.Message);

        Assert.Equal("PAIRED", DirectLinkState.PairingSucceeded("peer", "id", "Paired").Status);
        DirectLinkEvent failed = DirectLinkState.PairingFailed("peer", "id", "AuthenticationFailure");
        Assert.Equal("PAIRING_FAILED", failed.Status);
        Assert.Contains("AuthenticationFailure", failed.Message);
    }

    [Fact]
    public void DeviceOpeningIsObservableBeforeConnected()
    {
        DirectLinkEvent state = DirectLinkState.OpeningDevice("peer", "id");
        Assert.Equal("OPENING_DEVICE", state.Status);
        Assert.Equal("id", state.DeviceId);
    }
}
