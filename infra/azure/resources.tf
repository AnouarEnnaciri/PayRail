resource "azurerm_resource_group" "payrail" {
  name     = "rg-payrail-learning"
  location = "francecentral"

  tags = {
    project     = "PayRail"
    environment = "learning"
  }
}
