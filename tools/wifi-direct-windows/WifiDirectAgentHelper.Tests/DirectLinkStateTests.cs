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
    public void DisconnectTransitionsToReconnectState()
    {
        DirectLinkEvent state = DirectLinkState.Disconnected("LTM Server", "peer-1");
        Assert.Equal("RECONNECTING", state.Status);
        Assert.NotNull(state.Message);
    }
}
