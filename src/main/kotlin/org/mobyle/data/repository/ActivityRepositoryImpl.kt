package org.mobyle.data.repository

import org.mobyle.data.local.feed.ActivityDataSource
import org.mobyle.domain.repository.ActivityRepository

class ActivityRepositoryImpl(activityDataSource: ActivityDataSource) : ActivityRepository by activityDataSource
