<?php
	/* remove ill effects of magic quotes */

	if (get_magic_quotes_gpc()) {
		function stripslashes_deep($value) {
			$value = is_array($value) ?
				array_map('stripslashes_deep', $value) : stripslashes($value);
				return $value;
		}

		$_POST = array_map('stripslashes_deep', $_POST);
		$_GET = array_map('stripslashes_deep', $_GET);
		$_COOKIE = array_map('stripslashes_deep', $_COOKIE);
		$_REQUEST = array_map('stripslashes_deep', $_REQUEST);
	}

	require_once "functions.php";
	require_once "sessions.php";
	require_once "db-prefs.php";
	require_once "sanity_check.php";
	require_once "version.php";
	require_once "config.php";
	require_once "prefs.php";
	require_once "users.php";

	$link = db_connect(DB_HOST, DB_USER, DB_PASS, DB_NAME);

	$key = db_escape_string($_REQUEST['key']);

	$result = db_query($link, "SELECT snippet FROM ttirc_snippets WHERE ".DB_KEY_FIELD." = '$key'");

	header("Content-Type: text/plain; charset=utf-8");

	if (db_num_rows($result) != 0) {
		$text = db_fetch_result($result, 0, "snippet");

		print $text;

	} else {
		print "Snippet not found.";
	}

	db_close($link);

?>
