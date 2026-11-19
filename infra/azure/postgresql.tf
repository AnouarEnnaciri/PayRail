
variable "pg_admin_password" {
  type        = string
  sensitive   = true
  description = "Mot de passe administrateur PostgreSQL"
}

resource "azurerm_postgresql_flexible_server" "payrail" {
  name                = "pg-payrail-${substr(sha1(azurerm_resource_group.payrail.id), 0, 8)}"
  resource_group_name = azurerm_resource_group.payrail.name
  location            = azurerm_resource_group.payrail.location

  version                      = "17"
  sku_name                     = "B_Standard_B1ms"
  storage_mb                   = 32768
  auto_grow_enabled            = false
  backup_retention_days        = 7
  geo_redundant_backup_enabled = false

  administrator_login    = "payrail_admin"
  administrator_password = var.pg_admin_password

  public_network_access_enabled = false

  delegated_subnet_id = azurerm_subnet.postgres.id
  private_dns_zone_id = azurerm_private_dns_zone.postgres.id

  authentication {
    password_auth_enabled         = true
    active_directory_auth_enabled = false
  }

  depends_on = [
    azurerm_private_dns_zone_virtual_network_link.payrail
  ]
}

resource "azurerm_postgresql_flexible_server_database" "payrail" {
  name      = "payrail"
  server_id = azurerm_postgresql_flexible_server.payrail.id
}

resource "azurerm_postgresql_flexible_server_database" "simulator" {
  name      = "simulator"
  server_id = azurerm_postgresql_flexible_server.payrail.id
}
