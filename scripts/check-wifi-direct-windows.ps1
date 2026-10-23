$ErrorActionPreference = "Stop"

function Normalize-MacAddress([string] $MacAddress) {
    if ([string]::IsNullOrWhiteSpace($MacAddress)) {
        return $null
    }
    return ($MacAddress.Trim().Replace("-", ":").ToUpperInvariant())
}

$physicalAdapters = @(
    Get-NetAdapter -Physical |
        Where-Object { $_.InterfaceDescription -match "Wireless|Wi-Fi|802\.11" } |
        ForEach-Object {
            [pscustomobject]@{
                name = $_.Name
                description = $_.InterfaceDescription
                status = [string] $_.Status
                macAddress = Normalize-MacAddress $_.MacAddress
            }
        }
)

$directAdapters = @(
    Get-NetAdapter -IncludeHidden |
        Where-Object { $_.InterfaceDescription -match "Microsoft Wi-Fi Direct Virtual Adapter" } |
        Sort-Object @{ Expression = { if ($_.Status -eq "Up") { 0 } else { 1 } } }, ifIndex |
        ForEach-Object {
            [pscustomobject]@{
                name = $_.Name
                description = $_.InterfaceDescription
                status = [string] $_.Status
                macAddress = Normalize-MacAddress $_.MacAddress
                ifIndex = $_.ifIndex
            }
        }
)

$selected = $directAdapters | Select-Object -First 1
[pscustomobject]@{
    supported = $directAdapters.Count -gt 0
    adapterName = if ($null -ne $selected) { $selected.name } else { $null }
    status = if ($null -ne $selected) { $selected.status } else { "NotPresent" }
    macAddress = if ($null -ne $selected) { $selected.macAddress } else { $null }
    physicalAdapters = $physicalAdapters
    directAdapters = $directAdapters
} | ConvertTo-Json -Depth 5
