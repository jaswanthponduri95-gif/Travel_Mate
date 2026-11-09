# Destination-specific AI discovery update

- Hotel and restaurant page headings now use the active trip destination.
- The pages query the destination-filtered catalog first. If no matching records exist, they call `POST /api/ai/place-recommendations` with the selected destination and category.
- The backend uses the configured OpenAI-compatible AI provider and validates/limits the JSON response to eight suggestions.
- AI-generated suggestions do not invent ratings, prices, review counts, coordinates, phone numbers, or booking URLs. Cards label the items as AI suggestions and link to provider/map search using the selected destination.
- Restaurant map search uses the restaurant name and current trip destination; generated suggestions do not receive fabricated coordinates.
- Train booking links continue passing the active origin, destination, and date as query parameters. Some external booking providers may ignore text-only city parameters and require their own internal station IDs/autocomplete; TravelMate cannot force a third-party form to accept a route it does not support.

## Required configuration

AI discovery requires a working AI provider configured in TravelMate Settings. If the AI provider is not configured or returns invalid JSON, the pages show an empty state rather than substituting hotels/restaurants from another destination.
