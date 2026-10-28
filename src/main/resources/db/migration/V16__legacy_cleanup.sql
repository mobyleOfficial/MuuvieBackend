-- Legacy cleanup (B11 of the maratona roadmap). The unprefixed API is gone, and with it the
-- old movie likes (now user_movies.is_favorite, V3), the review likes (now activity_loves)
-- and the feature flags (the only one was new_navigation, which is now the only navigation).

DROP TABLE IF EXISTS movie_likes;
DROP TABLE IF EXISTS review_likes;
DROP TABLE IF EXISTS feature_flags;
