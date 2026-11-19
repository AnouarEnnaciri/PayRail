
variable "demo_alpha_password" {
  type      = string
  sensitive = true
}

variable "demo_beta_password" {
  type      = string
  sensitive = true
}

variable "demo_ops_password" {
  type      = string
  sensitive = true
}

variable "callback_hmac_secret" {
  type      = string
  sensitive = true
}

locals {
  postgres_host = azurerm_postgresql_flexible_server.payrail.fqdn
}

resource "azurerm_container_app" "simulator" {
  name                         = "ca-payrail-simulator"
  resource_group_name          = azurerm_resource_group.payrail.name
  container_app_environment_id = azurerm_container_app_environment.payrail.id
  revision_mode                = "Single"

  secret {
    name  = "db-password"
    value = var.pg_admin_password
  }

  secret {
    name  = "callback-secret"
    value = var.callback_hmac_secret
  }

  ingress {
    external_enabled = false
    target_port      = 8081
    transport        = "auto"

    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  template {
    min_replicas = 1
    max_replicas = 1

    container {
      name   = "simulator"
      image  = "ghcr.io/anouarennaciri/payrail-simulator:demo"
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "SIMULATOR_DB_URL"
        value = "jdbc:postgresql://${local.postgres_host}:5432/simulator?sslmode=require"
      }

      env {
        name  = "SIMULATOR_DB_USER"
        value = "payrail_admin"
      }

      env {
        name        = "SIMULATOR_DB_PASSWORD"
        secret_name = "db-password"
      }

      env {
        name  = "SIMULATOR_CALLBACK_URL"
        value = "http://ca-payrail-backend/api/settlement/callbacks"
      }

      env {
        name        = "SIMULATOR_CALLBACK_HMAC_SECRET"
        secret_name = "callback-secret"
      }
    }
  }
}

resource "azurerm_container_app" "backend" {
  name                         = "ca-payrail-backend"
  resource_group_name          = azurerm_resource_group.payrail.name
  container_app_environment_id = azurerm_container_app_environment.payrail.id
  revision_mode                = "Single"

  secret {
    name  = "db-password"
    value = var.pg_admin_password
  }

  secret {
    name  = "alpha-password"
    value = var.demo_alpha_password
  }

  secret {
    name  = "beta-password"
    value = var.demo_beta_password
  }

  secret {
    name  = "ops-password"
    value = var.demo_ops_password
  }

  secret {
    name  = "callback-secret"
    value = var.callback_hmac_secret
  }

  ingress {
    external_enabled = false
    target_port      = 8080
    transport        = "auto"

    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }

  template {
    min_replicas = 1
    max_replicas = 1

    container {
      name   = "backend"
      image  = "ghcr.io/anouarennaciri/payrail-backend:demo"
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "demo,public-demo"
      }

      env {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${local.postgres_host}:5432/payrail?sslmode=require"
      }

      env {
        name  = "PAYRAIL_DB_NAME"
        value = "payrail"
      }

      env {
        name  = "PAYRAIL_DB_USER"
        value = "payrail_admin"
      }

      env {
        name        = "PAYRAIL_DB_PASSWORD"
        secret_name = "db-password"
      }

      env {
        name        = "PAYRAIL_DEMO_CLIENT_ALPHA_PASSWORD"
        secret_name = "alpha-password"
      }

      env {
        name        = "PAYRAIL_DEMO_CLIENT_BETA_PASSWORD"
        secret_name = "beta-password"
      }

      env {
        name        = "PAYRAIL_DEMO_OPS_PASSWORD"
        secret_name = "ops-password"
      }

      env {
        name  = "PAYRAIL_OUTBOX_WORKER_ENABLED"
        value = "true"
      }

      env {
        name  = "PAYRAIL_OUTBOX_WORKER_POLL_MS"
        value = "1000"
      }

      env {
        name  = "PAYRAIL_SIMULATOR_BASE_URL"
        value = "http://ca-payrail-simulator"
      }

      env {
        name        = "PAYRAIL_CALLBACK_HMAC_SECRET"
        secret_name = "callback-secret"
      }
    }
  }
}
