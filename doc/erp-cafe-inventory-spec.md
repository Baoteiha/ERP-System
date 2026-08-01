# ERP Cafe — Inventory Module Spec

> Custom ERP inventory module for the Nguyệt Quế cafe. This is one module of a
> larger ERP system; this document scopes **inventory only**.

## Goal

Replace boring manual CRUD ingredient entry with an AI-assisted, camera-driven
intake flow. Inventory becomes the **single source of truth** for what's actually
in the store, and recipes are validated against it.

## Core Workflow

### 1. AI Camera Intake
- Employee points a phone camera at an incoming product (hover, no need to take a formal photo).
- An AI agent detects the product, searches online for details, and pulls a reference image.
- The system auto-restocks the item into inventory.

### 2. Auto-Fill Form + Missing-Info Prompts
- AI extracts what it can from the image: product name, quantity, expiry date, etc.
- Auto-fills the intake form.
- If a required field is missing or unclear, the AI flags it and prompts the employee to complete it manually.

### 3. Inventory as Source of Truth
- No manual ingredient list to maintain separately.
- When products arrive via the camera flow, ingredients are automatically added/updated in the ingredient table.
- Recipes reference this table — the system shows what's in stock and flags missing ingredients.
- You can't invent ingredients out of nowhere; they must originate from received stock.

### 4. Automated Supplier Alerts
- When stock drops below a threshold, the system automatically drafts and sends an email/text to the supplier.
- Message specifies what's needed and when (e.g., "Need X delivered tomorrow at 8 AM").

## Frontend — Interactive 3D Dashboard
- Web-based (easier to iterate on UI).
- **Three.js** for the 3D visualization (good community, plenty of tutorials).
- Live map of the cafe: storage areas, receiving dock, packages/deliveries moving in and out.
- **Clickable** — not just visual. Clicking a location surfaces real-time data:
  - Pending orders
  - Delivery status (received vs. in transit)
  - Missing items from shipments
  - Stock levels per area
- Location context makes the data more intuitive than menus/tables.

## Backend
- **Java / Spring Boot** (leveraging existing experience, smoother than switching to Django).
- REST API consumed by the phone client and the web dashboard.

## AI Stack
- Prefer self-hosted / low-cost. Options to evaluate:
  - Local MLX setup (Apple Silicon) for vision.
  - Ollama / open-source vision models (e.g. LLaVA-class) for product detection + data extraction.
  - Cheap cloud LLM as fallback — avoid expensive per-call APIs.

## Open Questions / Future Modules
- Quantity capture: fully from image, or partly manual?
- Expiry date handling and alerting.
- Later ERP modules (out of scope today): sales tracking, staffing/scheduling, etc.
- Low-stock alerts should also feed into the broader administrative system.
