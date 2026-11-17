# Endpoint checklist (generated)
Total: 190 endpoints — all mapped to the Java service (us.minitv.*)


## modules/public_flow.py  (/v1) - 46 endpoints
- [x] POST /v1/playback
- [x] POST /v1/playback/start
- [x] POST /v1/playback/progress
- [x] POST /v1/playback/complete
- [x] POST /v1/unlock
- [x] POST /v1/auth/email/code
- [x] GET /v1/auth/providers
- [x] POST /v1/auth/email/authenticate
- [x] POST /v1/auth/email/register
- [x] POST /v1/auth/email/login
- [x] POST /v1/auth/google
- [x] POST /v1/auth/facebook
- [x] POST /v1/auth/oauth/{provider}
- [x] GET /v1/me
- [x] PATCH /v1/me
- [x] GET /v1/me/ledger
- [x] POST /v1/me/checkin
- [x] POST /v1/me/ad-reward
- [x] GET /v1/tasks
- [x] GET /v1/me/tasks
- [x] POST /v1/me/tasks/{code}/claim
- [x] POST /v1/me/reminder
- [x] GET /v1/me/orders
- [x] GET /v1/plans
- [x] POST /v1/billing/webhook
- [x] POST /v1/billing/cancel
- [x] POST /v1/billing/checkout
- [x] POST /v1/billing/stripe/webhook
- [x] GET /v1/config
- [x] GET /v1/marketing
- [x] GET /v1/legal/{slug}
- [x] GET /v1/dramas/{drama_id}/comments
- [x] GET /v1/episodes/{episode_id}/comments
- [x] POST /v1/episodes/{episode_id}/comments
- [x] POST /v1/comments
- [x] DELETE /v1/comments/{comment_id}
- [x] POST /v1/reactions
- [x] GET /v1/me/favorites
- [x] GET /v1/me/messages
- [x] POST /v1/me/messages/read
- [x] GET /v1/dramas/{drama_id}/play
- [x] POST /v1/bunny/callback
- [x] GET /v1/search/trending
- [x] GET /v1/home/groups
- [x] GET /v1/me/history
- [x] GET /v1/me/lists/{kind}

## modules/content/public_router.py  (/v1) - 5 endpoints
- [x] GET /v1/preview
- [x] GET /v1/preview/dramas/{drama_id}
- [x] GET /v1/home
- [x] GET /v1/dramas/{drama_id}
- [x] GET /v1/search

## modules/recommend/router.py  (/v1) - 1 endpoints
- [x] GET /v1/feed

## modules/ops/router.py  (/admin/v1) - 24 endpoints
- [x] POST /admin/v1/auth/login
- [x] GET /admin/v1/auth/me
- [x] POST /admin/v1/auth/logout
- [x] POST /admin/v1/auth/password
- [x] GET /admin/v1/staff
- [x] POST /admin/v1/staff
- [x] PATCH /admin/v1/staff/{staff_id}
- [x] POST /admin/v1/staff/{staff_id}/status
- [x] POST /admin/v1/staff/{staff_id}/password
- [x] GET /admin/v1/suppliers
- [x] POST /admin/v1/suppliers
- [x] PATCH /admin/v1/suppliers/{supplier_id}
- [x] POST /admin/v1/suppliers/{supplier_id}/status
- [x] POST /admin/v1/suppliers/{supplier_id}/share
- [x] GET /admin/v1/suppliers/{supplier_id}/share
- [x] GET /admin/v1/dramas
- [x] POST /admin/v1/dramas
- [x] GET /admin/v1/dramas/{drama_id}
- [x] PATCH /admin/v1/dramas/{drama_id}
- [x] GET /admin/v1/tags
- [x] POST /admin/v1/tags
- [x] PATCH /admin/v1/tags/{tag_id}
- [x] DELETE /admin/v1/tags/{tag_id}
- [x] GET /admin/v1/search

## modules/ops/flow_router.py  (/admin/v1) - 68 endpoints
- [x] POST /admin/v1/dramas/{drama_id}/episodes
- [x] POST /admin/v1/dramas/{drama_id}/episodes/bulk
- [x] GET /admin/v1/dramas/{drama_id}/episodes
- [x] GET /admin/v1/episodes/{episode_id}/preview-url
- [x] POST /admin/v1/episodes/{episode_id}/upload-session
- [x] POST /admin/v1/episodes/{episode_id}/upload-headers
- [x] POST /admin/v1/episodes/{episode_id}/sync-status
- [x] POST /admin/v1/episodes/{episode_id}/upload-failed
- [x] POST /admin/v1/dev-tus/{video_id}
- [x] PATCH /admin/v1/dev-tus/{video_id}
- [x] DELETE /admin/v1/episodes/{episode_id}
- [x] PATCH /admin/v1/episodes/{episode_id}
- [x] POST /admin/v1/episodes/{episode_id}/publish
- [x] POST /admin/v1/episodes/{episode_id}/unpublish
- [x] POST /admin/v1/episodes/bulk-status
- [x] POST /admin/v1/dramas/{drama_id}/cover
- [x] DELETE /admin/v1/dramas/{drama_id}/cover
- [x] POST /admin/v1/episodes/{episode_id}/subtitle
- [x] POST /admin/v1/dramas/{drama_id}/status
- [x] POST /admin/v1/dramas/{drama_id}/schedule
- [x] POST /admin/v1/schedules/apply
- [x] POST /admin/v1/dramas/{drama_id}/delete
- [x] GET /admin/v1/dramas/{drama_id}/preview
- [x] POST /admin/v1/groups
- [x] GET /admin/v1/groups
- [x] PATCH /admin/v1/groups/{group_id}
- [x] POST /admin/v1/groups/{group_id}/dramas
- [x] GET /admin/v1/groups/{group_id}/dramas
- [x] DELETE /admin/v1/groups/{group_id}/dramas/{drama_id}
- [x] PATCH /admin/v1/groups/{group_id}/dramas/{drama_id}
- [x] GET /admin/v1/dramas/{drama_id}/groups
- [x] POST /admin/v1/copies
- [x] GET /admin/v1/copies
- [x] GET /admin/v1/tabs
- [x] POST /admin/v1/tabs
- [x] PATCH /admin/v1/tabs/{tab_id}
- [x] DELETE /admin/v1/tabs/{tab_id}
- [x] GET /admin/v1/banners
- [x] POST /admin/v1/banners
- [x] PATCH /admin/v1/banners/{banner_id}
- [x] DELETE /admin/v1/banners/{banner_id}
- [x] POST /admin/v1/hot-searches
- [x] POST /admin/v1/marketing/stats
- [x] GET /admin/v1/rewards/config
- [x] POST /admin/v1/rewards/config
- [x] GET /admin/v1/plans
- [x] GET /admin/v1/billing/settings
- [x] POST /admin/v1/billing/settings
- [x] POST /admin/v1/plans/{code}/price
- [x] GET /admin/v1/comments
- [x] GET /admin/v1/moderation/settings
- [x] POST /admin/v1/moderation/settings
- [x] POST /admin/v1/moderation/test
- [x] POST /admin/v1/comments/{comment_id}
- [x] GET /admin/v1/users
- [x] PATCH /admin/v1/users/{user_id}
- [x] POST /admin/v1/users/{user_id}/coins
- [x] GET /admin/v1/users/{user_id}/membership
- [x] POST /admin/v1/users/{user_id}/membership
- [x] DELETE /admin/v1/users/{user_id}/membership
- [x] GET /admin/v1/finance/orders
- [x] GET /admin/v1/finance/orders/{order_no}
- [x] GET /admin/v1/finance/ledger
- [x] GET /admin/v1/dashboard
- [x] POST /admin/v1/pushes
- [x] GET /admin/v1/pushes
- [x] POST /admin/v1/legal/{slug}
- [x] POST /admin/v1/stores

## modules/ops/review_router.py  (/admin/v1) - 4 endpoints
- [x] GET /admin/v1/reviews
- [x] POST /admin/v1/reviews/batch
- [x] POST /admin/v1/reviews/{drama_id}/decision
- [x] POST /admin/v1/reviews/{drama_id}/publish

## modules/ops/settlement_router.py  (/admin/v1) - 3 endpoints
- [x] GET /admin/v1/settlements
- [x] POST /admin/v1/settlements/generate
- [x] POST /admin/v1/settlements/{settlement_id}/status

## modules/ops/api_key_router.py  (/admin/v1) - 8 endpoints
- [x] GET /admin/v1/api-keys
- [x] GET /admin/v1/api-keys/{key_id}/events
- [x] GET /admin/v1/auth/keys
- [x] POST /admin/v1/api-keys
- [x] PATCH /admin/v1/api-keys/{key_id}
- [x] POST /admin/v1/api-keys/{key_id}/revoke
- [x] POST /admin/v1/api-keys/{key_id}/rotate
- [x] DELETE /admin/v1/api-keys/{key_id}

## modules/ops/partner_router.py  (/partner/v1) - 31 endpoints
- [x] GET /partner/v1/me
- [x] POST /partner/v1/auth/password
- [x] POST /partner/v1/auth/logout
- [x] GET /partner/v1/tags
- [x] GET /partner/v1/dramas
- [x] POST /partner/v1/dramas
- [x] GET /partner/v1/dramas/{drama_id}
- [x] PATCH /partner/v1/dramas/{drama_id}
- [x] POST /partner/v1/dramas/{drama_id}/cover
- [x] POST /partner/v1/dramas/{drama_id}/episodes
- [x] PATCH /partner/v1/episodes/{episode_id}
- [x] DELETE /partner/v1/episodes/{episode_id}
- [x] GET /partner/v1/episodes/{episode_id}/preview-url
- [x] POST /partner/v1/episodes/{episode_id}/upload-session
- [x] POST /partner/v1/dev-tus/{video_id}
- [x] PATCH /partner/v1/dev-tus/{video_id}
- [x] POST /partner/v1/episodes/{episode_id}/upload-headers
- [x] GET /partner/v1/episodes/{episode_id}/upload-status
- [x] POST /partner/v1/episodes/{episode_id}/upload-failed
- [x] DELETE /partner/v1/dramas/{drama_id}
- [x] POST /partner/v1/dramas/{drama_id}/submit
- [x] GET /partner/v1/stats/overview
- [x] GET /partner/v1/stats/dramas
- [x] GET /partner/v1/settlements
- [x] GET /partner/v1/api-keys
- [x] GET /partner/v1/auth/keys
- [x] GET /partner/v1/auth/key-info
- [x] POST /partner/v1/api-keys
- [x] PATCH /partner/v1/api-keys/{key_id}
- [x] POST /partner/v1/api-keys/{key_id}/rotate
- [x] POST /partner/v1/api-keys/{key_id}/revoke

