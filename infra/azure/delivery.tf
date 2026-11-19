
resource "azurerm_log_analytics_workspace" "payrail" {
  name                = "log-payrail-learning"
  resource_group_name = azurerm_resource_group.payrail.name
  location            = azurerm_resource_group.payrail.location

  sku               = "PerGB2018"
  retention_in_days = 30
}

resource "azurerm_container_app_environment" "payrail" {
  name                       = "cae-payrail-learning"
  resource_group_name        = azurerm_resource_group.payrail.name
  location                   = azurerm_resource_group.payrail.location
  infrastructure_subnet_id   = azurerm_subnet.container_apps.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.payrail.id
  logs_destination           = "log-analytics"

  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
  }
}
