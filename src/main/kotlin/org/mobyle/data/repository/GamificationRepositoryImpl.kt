package org.mobyle.data.repository

import org.mobyle.data.local.gamification.GamificationDataSource
import org.mobyle.domain.repository.GamificationRepository

class GamificationRepositoryImpl(gamificationDataSource: GamificationDataSource) : GamificationRepository by gamificationDataSource
