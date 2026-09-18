package com.betalgezia.omnivpn.data.local

import com.betalgezia.omnivpn.data.model.Subscription

fun SubscriptionEntity.toDomain() = Subscription(id=id, name=name, url=url, enabled=enabled, lastUpdatedAt=lastUpdatedAt)
fun Subscription.toEntity() = SubscriptionEntity(id=id, name=name, url=url, enabled=enabled, lastUpdatedAt=lastUpdatedAt)