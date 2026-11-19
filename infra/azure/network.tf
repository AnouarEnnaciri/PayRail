resource "azurerm_virtual_network" "payrail" {
  name                = "vnet-payrail-learning"
  location            = azurerm_resource_group.payrail.location
  resource_group_name = azurerm_resource_group.payrail.name
  address_space       = ["10.60.0.0/16"]
}

resource "azurerm_subnet" "postgres" {
  name                 = "snet-postgres"
  resource_group_name  = azurerm_resource_group.payrail.name
  virtual_network_name = azurerm_virtual_network.payrail.name
  address_prefixes     = ["10.60.1.0/28"]

  delegation {
    name = "postgres-delegation"

    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "container_apps" {
  name                 = "snet-container-apps"
  resource_group_name  = azurerm_resource_group.payrail.name
  virtual_network_name = azurerm_virtual_network.payrail.name
  address_prefixes     = ["10.60.2.0/27"]

  delegation {
    name = "container-apps-delegation"

    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_private_dns_zone" "postgres" {
  name                = "private.payrail.postgres.database.azure.com"
  resource_group_name = azurerm_resource_group.payrail.name
}

resource "azurerm_private_dns_zone_virtual_network_link" "payrail" {
  name                  = "payrail-dns-link"
  resource_group_name   = azurerm_resource_group.payrail.name
  private_dns_zone_name = azurerm_private_dns_zone.postgres.name
  virtual_network_id    = azurerm_virtual_network.payrail.id
  registration_enabled  = false
}