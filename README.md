# Habit Tracker

Offline-first personal Android habit tracker built with Kotlin, Jetpack Compose, Room, and MVVM.

## Current scope
- Dynamic task master table with `NUMBER`, `BOOLEAN`, `TEXT`, and `DURATION` value types
- Daily record root and mapped daily record items
- Attachment table structure for task masters and daily record items
- Compose screens for calendar overview, daily entry, and monthly statistics
- Room repository layer with transactional daily record persistence

## Next implementation steps
- Add loading/editing of existing records when a date is selected
- Connect attachment picker using Storage Access Framework
- Add chart polish and richer monthly comparison views
- Add DAO and ViewModel tests
- Generate Gradle wrapper from Android Studio or local Gradle installation
