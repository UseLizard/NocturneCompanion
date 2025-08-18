# GlassCard Component Usage Guide

## Overview

The `GlassCard` component provides a consistent glass morphism effect across all tabs in the NocturneCompanion app. Instead of manually styling each `Card`, use the pre-built `GlassCard` variants for consistent design.

## Component Structure

```kotlin
// Location: app/src/main/java/com/paulcity/nocturnecompanion/ui/components/GlassCard.kt

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glassType: GlassType = GlassType.Primary,
    isActive: Boolean = true,
    enableScaleAnimation: Boolean = true,
    scaleValue: Float = 1.02f,
    animationDuration: Int = 200,
    cornerRadius: Dp = 16.dp,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
)
```

## Quick Usage - Convenience Components

### 1. PrimaryGlassCard
**Use for:** Main content cards, primary information displays

```kotlin
PrimaryGlassCard(
    modifier = Modifier.fillMaxWidth()
) {
    Text("Your content here")
    // Content automatically gets proper padding and glass effect
}
```

### 2. SurfaceGlassCard  
**Use for:** Secondary content, supporting information

```kotlin
SurfaceGlassCard(
    modifier = Modifier.fillMaxWidth()
) {
    Text("Secondary content")
}
```

### 3. MinimalGlassCard
**Use for:** Small metrics, subtle info cards, no animation

```kotlin
MinimalGlassCard(
    modifier = Modifier.weight(1f)
) {
    Text("Metric: 123")
}
```

## Replacing Existing Cards

### Before (Old Card Implementation):
```kotlin
Card(
    modifier = Modifier
        .fillMaxWidth()
        .scale(scale),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
) {
    Column(modifier = Modifier.padding(20.dp)) {
        // Your content
    }
}
```

### After (New GlassCard):
```kotlin
PrimaryGlassCard(
    modifier = Modifier.fillMaxWidth()
) {
    // Your content - padding handled automatically
}
```

## Glass Types

| Type | Use Case | Alpha | Example |
|------|----------|-------|---------|
| `GlassType.Primary` | Main content cards | 0.15f | Device info, status cards |
| `GlassType.Surface` | Secondary content | 0.10f | Settings panels, info displays |
| `GlassType.Secondary` | Supporting content | 0.12f | Helper cards |
| `GlassType.Tertiary` | Accent content | 0.12f | Highlights, special info |
| `GlassType.Error` | Error states | 0.12f | Error messages |
| `GlassType.Success` | Success states | 0.10f | Success indicators |
| `GlassType.Minimal` | Very subtle cards | 0.08f | Small metrics, chips |
| `GlassType.Custom(color, alpha)` | Custom styling | Custom | Special cases |

## Advanced Usage

### Custom Glass Effect
```kotlin
GlassCard(
    glassType = GlassType.Custom(
        color = Color.Blue, 
        alpha = 0.2f
    ),
    cornerRadius = 12.dp,
    enableScaleAnimation = false
) {
    // Custom styled content
}
```

### Conditional Active State
```kotlin
PrimaryGlassCard(
    isActive = isConnected, // Changes opacity and animation
    modifier = Modifier.fillMaxWidth()
) {
    Text("Connection Status")
}
```

## Migration Steps

1. **Import the component:**
   ```kotlin
   import com.paulcity.nocturnecompanion.ui.components.PrimaryGlassCard
   import com.paulcity.nocturnecompanion.ui.components.SurfaceGlassCard
   import com.paulcity.nocturnecompanion.ui.components.MinimalGlassCard
   ```

2. **Replace existing Cards:**
   - Main content cards → `PrimaryGlassCard`
   - Secondary cards → `SurfaceGlassCard`
   - Small metrics → `MinimalGlassCard`

3. **Remove manual styling:**
   - Remove `animateFloatAsState` and `scale` modifiers
   - Remove `CardDefaults.cardColors` with alpha
   - Remove manual `padding()` from content
   - Remove `elevation` and `shape` parameters

4. **Update imports:**
   - Remove unused animation and shape imports
   - Add GlassCard imports

## Benefits

- ✅ **Consistent Design**: All cards have the same glass effect
- ✅ **Easy Maintenance**: Edit one file to change all card styles
- ✅ **Reduced Boilerplate**: No need to repeat styling code
- ✅ **Better Performance**: Shared animation and styling logic
- ✅ **Type Safety**: Predefined glass types prevent styling errors
- ✅ **Responsive**: Automatically handles active/inactive states

## Example Tab Migration

See the updated `StatusTab.kt` and `DevicesTab.kt` for examples of the migration pattern.