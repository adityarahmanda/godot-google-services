@tool
extends EditorPlugin

# A class member to hold the export plugin during its lifecycle.
var export_plugin : AndroidExportPlugin

func _enter_tree():
	# Initialization of the plugin goes here.
	export_plugin = AndroidExportPlugin.new()
	add_export_plugin(export_plugin)


func _exit_tree():
	# Clean-up of the plugin goes here.
	remove_export_plugin(export_plugin)
	export_plugin = null


class AndroidExportPlugin extends EditorExportPlugin:

	func _supports_platform(platform):
		if platform is EditorExportPlatformAndroid:
			return true
		return false

	func _get_android_libraries(platform, debug):
		if debug:
			return PackedStringArray(["googleservices/bin/debug/GoogleServices.debug.aar"])
		else:
			return PackedStringArray(["googleservices/bin/release/GoogleServices.release.aar"])

	func _get_name():
		return "GoogleServicesPlugin"
