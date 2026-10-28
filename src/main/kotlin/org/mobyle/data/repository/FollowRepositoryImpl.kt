package org.mobyle.data.repository

import org.mobyle.data.local.feed.FollowDataSource
import org.mobyle.domain.repository.FollowRepository

class FollowRepositoryImpl(followDataSource: FollowDataSource) : FollowRepository by followDataSource
