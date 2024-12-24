package com.kawler.parrallelmatrix.fragments

import androidx.fragment.app.viewModels
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.kawler.parrallelmatrix.R
import com.kawler.parrallelmatrix.databinding.FragmentMainDeviceBinding

class MainDeviceFragment : Fragment() {

    private var _binding: FragmentMainDeviceBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MainDeviceViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[MainDeviceViewModel::class.java]

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            binding.progressBar.progress = progress
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            binding.resultTextView.text = result
        }

        viewModel.calculationInProgress.observe(viewLifecycleOwner){ isInProgress ->
            binding.progressBar.visibility = if(isInProgress) View.VISIBLE else View.INVISIBLE
        }

        binding.startButton.setOnClickListener {
            startCalculation()
        }
    }

    private fun startCalculation() {
        val rowsStr = binding.matrixRowsEditText.text.toString()
        val colsStr = binding.matrixColsEditText.text.toString()
        val minStr = binding.minElementEditText.text.toString()
        val maxStr = binding.maxElementEditText.text.toString()

        if (rowsStr.isEmpty() || colsStr.isEmpty() || minStr.isEmpty() || maxStr.isEmpty()){
            Toast.makeText(requireContext(), "Please fill in all the fields", Toast.LENGTH_SHORT).show()
            return
        }


        val rows = rowsStr.toIntOrNull()
        val cols = colsStr.toIntOrNull()
        val minVal = minStr.toIntOrNull()
        val maxVal = maxStr.toIntOrNull()



        if (rows == null || cols == null || minVal == null || maxVal == null ) {
            Toast.makeText(requireContext(), "Invalid number input", Toast.LENGTH_SHORT).show()
            return
        }


        viewModel.startCalculation(rows, cols, minVal, maxVal)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}