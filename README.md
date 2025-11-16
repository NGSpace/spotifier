Spotifier, add spotify information to your Hudder hud!

## Set up instructions
1. Go to https://developer.spotify.com/dashboard
2. Press "Create App"
3. Set the "Redirect URI" to `http://127.0.0.1:8888/callback`
4. Give it a name and description of your choosing (It won't affect spotifier)
5. Press "Save"
6. Copy "Client ID"
7. Go into Spotifier's in game settings
8. Go into "Spotify Auth"
9. Paste your app's Client ID into the Client ID setting
10. Once you save, a window shall open asking you to authenticate (If not then the URL will be printed in the game's log so you can enter it manually)
11. Press "Agree"
12. Use spotifier

## Variables added by spotifier:

```
// Spotifier
{has_spotifier} - Will always return true
{spotifier_connected} - Is spotifier connected and authenticated to Spotify's servers.
{spotifier} - The spotifier object (Don't use it unless you know what you're doing)

// Booleans
{spotifier_paused} - Is the song paused.
{spotifier_shuffle} - Is the playlist shuffled.

// Strings
{spotifier_repeat} - The repeat status (off, track, context) 
{spotifier_track} - The name of the track
{spotifier_album} - The name of the album
{spotifier_album_type} - The type of the album
{spotifier_playlist} - The name of the playlist

// Numbers
{spotifier_progress} - The progress of the song (in milliseconds)
{spotifier_duration} - The duration of the song (in milliseconds)
{spotifier_data_age} - How old is the data provided by spotifier (in milliseconds)

// Arrays
{spotifier_artists} - An array of the artists that are credited in the song (Strings)
{spotifier_queue} - An array of song elements (Contains properties track, artists, album, duration, album_type)
```

## Example hud
```
;mute;

// Ensure the hud only runs if spotifier is enabled and connected
#if has_spotifier==unset||!spotifier_connected||spotifier==null
	{break}

// ======================
// CONFIGURATION
// ======================

// Enable / disable all colorization
{colored=true}

// Whether the time display (clock) should be colorized
{color_clock=false}

// How fast colors "move" (in ms between shifts)
{colored_speed=150}

// If true, each string gets a single uniform color.
// If false, each character gets its own color (rainbow effect).
{uniform_playing_color=false}

// Ordered list of Minecraft color codes used for the rainbow
{colors=["4","c","6","e","2","a","b","3","1","9","d","5"]}

// Whether to randomize the order of the colors instead of cycling deterministically
{random_colors=false}

// How many upcoming songs from the queue to display (0 = don't show queue)
{max_queue_length=3}

// Fallback for undefined / null playlist names
{playlist_name = spotifier_playlist}
#if playlist_name==null
    {playlist_name = "Unknown Playlist"}



// ======================
// FUNCTIONS
// ======================

// shuffle(Array original)
// Returns a new array with the same elements as `original` but in random order.
#def shuffle, original
    {_len = length(original)}
    // Copy original into _out
    {_out = array(_len, 0)}
    #for i in range(_len)
        {_out[i] = original[i]}
    // Shuffle _out in-place
    {_i = _len - 1}
    #while _i > 0
        {_j = rng(0, _i + 1)} // 0.._i inclusive
        {_tmp = _out[_i]}
        {_out[_i] = _out[_j]}
        {_out[_j] = _tmp}
        {_i = _i - 1}
    ;return, _out;


// colorify(String str, Boolean unified)
// Returns `str` colored with the global `colors` array.
// - If `colored` is false, the string is returned unchanged.
// - If `unified` is true, all characters share the same color for this frame.
// - If `unified` is false, each character is offset, giving a rainbow effect.
#def colorify, str, unified
	#if !colored
		;return, str;
	{output=""}
	#for i in range(length(str))
		{_char=str.charAt(i)}
		// Base index is the global colorcodeindex
		{_indx=colorcodeindex}
		// When not unified, offset by character index for a gradient / rainbow
		#if !unified
			{_indx=_indx+i}
		// Compute color index into the colors array
		{_color_code=colors[(_indx%length(colors))]}
		// If random_colors is enabled, use the shuffled array instead
		#if random_colors
			{_color_code=random_colors_array[(_indx%length(random_colors_array))]}
		// Append the Minecraft color code and the character
		{output=output+"§"+_color_code+_char}
	;return, output;


// clock(Number prog)
// Converts a progress value in milliseconds into a MM:SS string.
#def clock, prog
	{_total=(prog/1000)}
	{_min=int(_total/60)}
	{_sec=int(_total%60)}
	// Pad seconds with a leading zero if needed
	#if (_sec<10)
		{_sec = "0" + _sec}
	;return, _min+":"+_sec;



// ======================
// COLOR STATE & ANIMATION
// ======================

// Initialize random_colors_array with the base colors once, when needed
#if random_colors_array==unset
	{random_colors_array=colors}

// Initialize the color animation state the first time the HUD runs
#if lastcolormove==unset
	{lastcolormove=time}
	{colorcodeindex=0}

// Advance the color index periodically (based on colored_speed)
// Only advance when Spotifier is not paused.
#if time-lastcolormove>colored_speed&&!spotifier_paused
	{lastcolormove=time}
	{colorcodeindex=colorcodeindex+1}
	// Optionally reshuffle the colors every shift when random_colors is true
	#if random_colors
		{random_colors_array=shuffle(colors)}



// ======================
// QUEUE BUILDING
// ======================

// String that will hold the textual representation of the upcoming queue
{_queue_output=""}

// Only attempt to show the queue if max_queue_length is non-zero
#if max_queue_length!=0
	// If the queue object is defined, build the list of next songs
	#if spotifier_queue!=unset
		{_queue_output="Next Songs:\n"}
		#for i in range(min(max_queue_length,length(spotifier_queue)))
			{_queue_output=_queue_output+(spotifier_queue[i].track)+"\n"}
		// If the queue exists but is empty, show a friendly message
		#if length(spotifier_queue)==0
			{_queue_output="Spotify queue is empty!"}
	// If the queue is not defined at all, show a different message
	#if spotifier_queue==unset
		{_queue_output="Spotify queue is not defined!"}



// ======================
// PROGRESS / CLOCK
// ======================

// Start from Spotifier's reported progress
{actual_prog=spotifier_progress}

// If playback is ongoing, compensate with data age to approximate "now"
#if !spotifier_paused
	{actual_prog=actual_prog+spotifier_data_age}

// Prebuild the full "current/total" clock string once
{clock_str=clock(actual_prog)+"/"+clock(spotifier_duration)}


// LAYOUT

;topleft;
Playing {colorify(spotifier_track, uniform_playing_color)} &r(%spotifier_album_type=="single", "Single", "From '" + spotifier_album+ "'"%)%spotifier_paused, " (Paused)"%%spotifier_repeat=="track", " on repeat!"%
By {colorify(spotifier_artists[0], uniform_playing_color)}
In {colorify(playlist_name, uniform_playing_color)}&r%spotifier_shuffle, " (Shuffled)"%%spotifier_repeat=="context", " (Repeat)"%

%!spotifier_paused&&color_clock, "{colorify(clock_str, true)}", "{clock_str}"%

{_queue_output.trim()}
```
