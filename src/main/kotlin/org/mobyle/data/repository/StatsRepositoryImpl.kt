package org.mobyle.data.repository

import org.mobyle.data.local.stats.StatsDataSource
import org.mobyle.domain.repository.StatsRepository

class StatsRepositoryImpl(statsDataSource: StatsDataSource) : StatsRepository by statsDataSource
