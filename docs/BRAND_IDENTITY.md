# Artio visual identity

This document is the source of truth for the visual identity used by the Artio Storefront and Backoffice. The supplied Artio brand board and application wireframes define the direction: a confident, developer-focused commerce platform with a dark navy foundation, bright indigo, violet and cyan accents, and clear Sora typography.

## Brand character

- **Promise:** Build. Plugin. Scale. Grow.
- **Positioning:** The modular commerce platform for ambitious businesses.
- **Voice:** Direct, capable and optimistic. Product UI copy should be short and specific.
- **Visual tone:** Technical and polished, with luminous gradients used for emphasis. Keep operational information calm and easy to scan.

## Colors

| Token | Value | Use |
|---|---|---|
| Deep navy | `#0F172A` | Brand foundation and dark page surfaces |
| Indigo | `#6366F1` | Primary interactive accent |
| Violet | `#8B5CF6` | Secondary accent and gradient endpoint |
| Cyan | `#06B6D4` | Focus, highlights and gradient start |
| Graphite | `#1E293B` | Raised surfaces and navigation |
| Slate | `#334155` | Borders and quiet separators |
| Light | `#E2E8F0` | Primary text and neutral contrast |

Use the shared cyan-to-indigo-to-violet gradient for primary actions and high-level brand emphasis. Keep body text and data labels high contrast. Avoid bringing back the former green and beige palette or using the gradient as a background for dense operational content.

## Typography

- **Primary family:** Sora, with Inter and system sans-serif fallbacks.
- **Headings:** Sora Medium or Semibold. Use restrained negative tracking and generous line height.
- **Body and controls:** Sora Regular or Medium. Keep labels readable at compact sizes.
- **Brand display:** Sora Bold. Preserve the Artio wordmark proportions and the registered mark.

The applications currently load Sora from Google Fonts and include local system fallbacks. If the font service is unavailable, the UI must remain legible and correctly laid out using the fallbacks.

## Components and layout

- Use dark navy page backgrounds, slightly lighter graphite or navy cards, and slate borders.
- Use rounded, restrained card corners and clear spacing between navigation, content and data groups.
- Reserve cyan and violet for emphasis, links, status indicators and selected navigation. Do not use color alone to convey status.
- Keep buttons, inputs, selects and focus rings consistent across Storefront and Backoffice.
- Preserve responsive layouts, keyboard focus visibility, accessible contrast and reduced-motion expectations.
- Storefront can use product photography and editorial layouts while retaining the shared palette, type and interaction language.
- Backoffice should favor compact, scannable tables and panels. Reserve gradients for primary actions and featured overview areas.

## Logo assets and implementation

- The Storefront icon is maintained at `artio-storefront/src/assets/icons/artio.svg`.
- The Storefront and Backoffice global style sheets define the shared design tokens and component styling. Update both when a shared token changes.
- Use the Artio mark and wordmark as one identity. Do not replace them with an unrelated icon or product-specific color system.
- Keep privacy, authentication, setup and other security-sensitive UI consistent with the brand while preserving clear explanations and secure defaults.

## Developer first-run setup

When the API reports the explicit non-production developer profile, the Backoffice first-run wizard pre-fills local demo identity and setup values. Its developer badge and helper text must make that context clear. The local demo setup token and demo administrator password are documented in `docs/module-inventory.md`; they are restricted by the API container startup script to `dev` or `development` profiles that do not include `production`. Never copy those values into a production deployment.

## Change process

When changing this system, update the shared CSS tokens in both Angular applications and this document together. Review Storefront, Backoffice, setup and consent screens at desktop and mobile widths before release.
