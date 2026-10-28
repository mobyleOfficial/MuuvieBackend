package org.mobyle.data.repository

import org.mobyle.data.local.feed.NotificationDataSource
import org.mobyle.domain.repository.NotificationRepository

class NotificationRepositoryImpl(notificationDataSource: NotificationDataSource) : NotificationRepository by notificationDataSource
