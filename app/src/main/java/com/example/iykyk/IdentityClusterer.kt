package com.example.iykyk

data class Person(
    val id: Int,
    val tracks: MutableList<Track> = mutableListOf()
) {
    val appearanceCount get() = tracks.size
}

class IdentityClusterer(private val threshold: Float = 0.42f) {

    fun cluster(tracks: List<Track>): List<Person> {
        val sorted = tracks.sortedBy { it.startMs }
        val people = mutableListOf<Person>()
        var nextId = 0

        for (track in sorted) {
            var bestPerson: Person? = null
            var bestSim = 0f

            for (person in people) {
                val overlapsInTime = person.tracks.any { existing ->
                    existing.startMs <= track.endMs && track.startMs <= existing.endMs
                }
                if (overlapsInTime) continue

                // Compare with every track in the person and keep the strongest match.
                val sim = person.tracks.maxOf { existing ->
                    cosineSim(track.bestEmbedding, existing.bestEmbedding)
                }
                if (sim > bestSim) {
                    bestSim = sim
                    bestPerson = person
                }
            }

            if (bestPerson != null && bestSim >= threshold) {
                bestPerson.tracks.add(track)
            } else {
                people.add(Person(nextId++).apply { this.tracks.add(track) })
            }
        }
        return people
    }
}
