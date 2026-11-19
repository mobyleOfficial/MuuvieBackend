package org.mobyle.data.repository

import org.mobyle.data.local.moderation.ModerationDataSource
import org.mobyle.domain.repository.ModerationRepository

class ModerationRepositoryImpl(moderationDataSource: ModerationDataSource) : ModerationRepository by moderationDataSource
